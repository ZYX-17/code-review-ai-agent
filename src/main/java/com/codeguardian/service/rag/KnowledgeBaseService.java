package com.codeguardian.service.rag;

import com.codeguardian.repository.KnowledgeDocumentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 知识库服务 (RAG核心实现)
 * <p>
 * 该服务实现了 Hybrid Retrieval (混合检索) + Rerank (重排序) 策略。
 * 使用 PGVector 作为向量数据库，同时维护内存中的 BM25 索引以支持混合检索。
 */
@Service
@Lazy
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private VectorStore vectorStore; // Injected (PGVector)
    private final KnowledgeDocumentRepository repository;
    private final MinioStorageService minioStorageService;
    private final JdbcTemplate jdbcTemplate;
    @org.springframework.beans.factory.annotation.Value("${app.rag.vectorize-on-startup:false}")
    private boolean vectorizeOnStartup;
    
    // 原始文档列表 (用于 BM25 构建)
    private List<KnowledgeDocument> documents = new ArrayList<>();
    
    // BM25 索引结构
    private Map<String, List<Integer>> invertedIndex = new HashMap<>();
    private List<Map<String, Integer>> docTermFreqs = new ArrayList<>();
    private List<Integer> docLengths = new ArrayList<>();
    private double avgDocLength = 0;
    
    // BM25 算法参数
    private static final double k1 = 1.5;
    private static final double b = 0.75;

    @PostConstruct
    public void init() {
        try {
            // 0. Check and Fix Vector Schema (Auto-healing for dimension mismatch)
            checkAndFixVectorSchema();

            // Skip eager vector search verification to avoid triggering embedding model download at startup

            // 1. 从数据库加载文档
            List<KnowledgeDocument> dbDocs = repository.findAll();
            
            // 检查是否有缺失类别的旧数据
            boolean hasNullCategory = false;
            for (KnowledgeDocument doc : dbDocs) {
                if (doc.getCategory() == null) {
                    doc.setCategory("CODE_STYLE"); // 默认值
                    repository.save(doc);
                    hasNullCategory = true;
                }
            }
            if (hasNullCategory) {
                log.info("Fixed missing categories for existing documents.");
                dbDocs = repository.findAll(); // 重新加载
            }
            
            if (dbDocs.isEmpty()) {
                log.info("Database is empty. Loading default knowledge base from rules.json...");
                loadDefaultKnowledgeBase();
                dbDocs = repository.findAll();
            } else {
                // 确保默认规则是最新的（覆盖旧数据）
                log.info("Reloading default knowledge base to ensure data consistency...");
                loadDefaultKnowledgeBase();
                dbDocs = repository.findAll();
                
                log.info("Loaded {} documents from database.", dbDocs.size());
            }
            this.documents = dbDocs;
            
            // 2. 构建 BM25 索引
            buildIndices();
            
        } catch (Exception e) {
            log.warn("KnowledgeBaseService initialization failed: {}", e.getMessage());
        }
        if (vectorizeOnStartup) {
            new Thread(() -> {
                try {
                    for (KnowledgeDocument doc : this.documents) {
                        vectorizeDocument(doc);
                    }
                } catch (Exception ex) {
                    log.warn("Background vectorization failed: {}", ex.getMessage());
                }
            }, "rag-vectorize-background").start();
        }
    }

    private void checkAndFixVectorSchema() {
        try {
            // Check if vector_store table exists
            Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'vector_store'", Integer.class);
            
            if (count != null && count > 0) {
                // Check embedding column type
                String type = jdbcTemplate.queryForObject(
                    "SELECT format_type(atttypid, atttypmod) FROM pg_attribute " +
                    "WHERE attrelid = 'vector_store'::regclass AND attname = 'embedding'", String.class);
                
                log.info("Current vector_store.embedding type: {}", type);
                
                // If type exists and is NOT vector(384), fix it
                if (type != null && !type.contains("(384)")) {
                    log.warn("Detected incorrect vector dimensions (expected 384). Fixing schema...");
                    
                    // Option 1: Drop table (Cleanest, but requires restart or re-init logic which is hard)
                    // Option 2: Alter table (Preserves table, but clears data)
                    
                    log.info("Truncating vector_store table...");
                    jdbcTemplate.execute("TRUNCATE TABLE vector_store");
                    
                    log.info("Altering embedding column to vector(384)...");
                    // Using USING clause to cast (though truncation makes it empty)
                    jdbcTemplate.execute("ALTER TABLE vector_store ALTER COLUMN embedding TYPE vector(384)");
                    
                    log.info("Schema fixed successfully. Please re-upload documents if needed.");
                }
            }
        } catch (Exception e) {
            log.error("Failed to check/fix vector schema: {}", e.getMessage());
            // Don't block startup
        }
    }

    /**
     * 加载默认知识库 (knowledge/rules.json)
     */
    private void loadDefaultKnowledgeBase() {
        try {
            ClassPathResource resource = new ClassPathResource("knowledge/rules.json");
            if (resource.exists()) {
                List<java.util.Map<String, Object>> items = objectMapper.readValue(resource.getInputStream(), new TypeReference<List<java.util.Map<String, Object>>>() {});

                for (java.util.Map<String, Object> item : items) {
                    String id = item.get("id") != null ? String.valueOf(item.get("id")) : UUID.randomUUID().toString();
                    String title = item.get("title") != null ? String.valueOf(item.get("title")) : "未命名文档";
                    String content = item.get("content") != null ? String.valueOf(item.get("content")) : "";
                    String solution = item.get("solution") != null ? String.valueOf(item.get("solution")) : null;
                    String catCode = item.get("category") != null ? String.valueOf(item.get("category")).toUpperCase() : "CODE_STYLE";

                    KnowledgeDocument doc = KnowledgeDocument.builder()
                            .id(id)
                            .title(title)
                            .content(content)
                            .solution(solution)
                            .category(catCode)
                            .createTime(java.time.LocalDateTime.now())
                            .metadata(java.util.Map.of("source", "default_rules"))
                            .build();
                    saveDocument(doc, false);
                }
            } else {
                log.warn("Knowledge Base file not found: knowledge/rules.json");
            }
        } catch (IOException e) {
            log.error("Failed to load Knowledge Base", e);
        }
    }
    
    /**
     * 保存文档到 DB 和 VectorStore
     */
    private void saveDocument(KnowledgeDocument doc) {
        saveDocument(doc, true);
    }

    private void saveDocument(KnowledgeDocument doc, boolean vectorize) {
        repository.save(doc);
        this.documents.add(doc);
        if (!vectorize) {
            return;
        }
        vectorizeDocument(doc);
    }

    private void vectorizeDocument(KnowledgeDocument doc) {
        if (vectorStore == null) {
            return;
        }
        String content = doc.getTitle() + "\n" + doc.getContent();
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(
            List.of(new Document(doc.getId(), content, doc.getMetadata() != null ? new HashMap<>(doc.getMetadata()) : new HashMap<>()))
        );
        if (chunks.isEmpty()) {
            log.warn("Document splitting resulted in 0 chunks for doc: {}", doc.getTitle());
            return;
        }
        for (Document chunk : chunks) {
            chunk.getMetadata().put("source_doc_id", doc.getId());
        }
        try {
            vectorStore.add(chunks);
        } catch (Exception e) {
            log.error("Failed to add document to Vector Store: {}", e.getMessage(), e);
        }
    }

    /**
     * 上传并处理文档 (支持多种格式)
     */
    public void uploadDocument(MultipartFile file) throws IOException {
        log.info("Starting document upload process for file: {}, size: {}", file.getOriginalFilename(), file.getSize());
        try {
            // Upload to MinIO
            log.info("Uploading file to MinIO...");
            String objectName = minioStorageService.uploadFile(file);
            log.info("File uploaded to MinIO. ObjectName: {}", objectName);

            log.info("Extracting text from document using Tika...");
            Resource resource = new InputStreamResource(file.getInputStream());
            TikaDocumentReader tikaReader = new TikaDocumentReader(resource);
            List<Document> tikaDocs = tikaReader.get();
            
            // Combine all text from Tika documents
            StringBuilder textBuilder = new StringBuilder();
            for (Document doc : tikaDocs) {
                textBuilder.append(doc.getContent()).append("\n");
            }
            String text = textBuilder.toString();
            log.info("Text extraction completed. Text length: {}", text.length());
            
            String id = UUID.randomUUID().toString();
            KnowledgeDocument doc = KnowledgeDocument.builder()
                .id(id)
                .title(file.getOriginalFilename())
                .content(text)
                .category("CODE_STYLE")
                .minioBucketName(minioStorageService.getBucketName())
                .minioObjectName(objectName)
                .fileSize(file.getSize())
                .contentType(file.getContentType())
                .createTime(java.time.LocalDateTime.now())
                .metadata(Map.of(
                    "filename", file.getOriginalFilename(), 
                    "type", file.getContentType() != null ? file.getContentType() : "unknown",
                    "bucket", minioStorageService.getBucketName(),
                    "object", objectName
                ))
                .build();
            
            log.info("Saving document metadata to database and vector store...");
            // 保存并更新索引
            saveDocument(doc);
            log.info("Document saved successfully. ID: {}", id);
            
            // 重新构建 BM25
            log.info("Updating BM25 index...");
            addToBM25Index(doc, this.documents.size() - 1);
            log.info("BM25 index updated.");
        } catch (Exception e) {
            log.error("Error during document upload process", e);
            throw e; 
        }
    }
    
    public Page<KnowledgeDocument> getDocuments(int page, int size, String keyword) {
        // 使用 Sort.unsorted()，因为排序已经在 Repository 的 @Query 中指定了
        Pageable pageable = PageRequest.of(page - 1, size, Sort.unsorted());
        
        if (keyword != null && !keyword.isEmpty()) {
            return repository.findByTitleContainingIgnoreCaseNullsLast(keyword, pageable);
        }
        return repository.findAllNullsLast(pageable);
    }

    public KnowledgeDocument getDocumentById(String id) {
        return repository.findById(id).orElse(null);
    }

    /**
     * 删除文档
     */
    public void deleteDocument(String id) {
        Optional<KnowledgeDocument> docOpt = repository.findById(id);
        if (docOpt.isEmpty()) {
            return;
        }
        KnowledgeDocument doc = docOpt.get();

        // 1. Delete from MinIO
        if (doc.getMinioObjectName() != null) {
            try {
                minioStorageService.removeFile(doc.getMinioObjectName());
            } catch (Exception e) {
                log.error("Failed to delete file from MinIO: {}", e.getMessage());
            }
        }

        // 2. Delete from DB
        repository.deleteById(id);

        // 3. Update memory cache
        this.documents.removeIf(d -> d.getId().equals(id));
        
        // 4. Rebuild BM25 indices
        buildIndices();
    }

    public InputStream getFileStream(String objectName) {
        return minioStorageService.getFile(objectName);
    }

    public Map<String, Object> getStats() {
        long count = repository.count();
        // Calculate total size if possible, or just return count for now
        // Assuming we want a simple stats object
        Map<String, Object> stats = new HashMap<>();
        stats.put("documentCount", count);
        stats.put("name", "默认知识库");
        stats.put("description", "系统默认的向量知识库，存储所有上传的代码规范和技术文档");
        stats.put("createTime", java.time.LocalDateTime.now()); // Placeholder
        return stats;
    }
    
    public List<KnowledgeDocument> getAllDocuments() {
        return this.documents;
    }

    /**
     * 构建 BM25 倒排索引和统计信息 (全量)
     */
    private void buildIndices() {
        if (documents.isEmpty()) return;
        
        invertedIndex.clear();
        docTermFreqs.clear();
        docLengths.clear();
        long totalLength = 0;
        
        for (int i = 0; i < documents.size(); i++) {
            addToBM25Index(documents.get(i), i);
            totalLength += docLengths.get(i);
        }
        
        avgDocLength = (double) totalLength / documents.size();
        log.info("Built BM25 Index for {} documents", documents.size());
    }
    
    private void addToBM25Index(KnowledgeDocument doc, int index) {
        String text = (doc.getTitle() + " " + doc.getContent()).toLowerCase();
        List<String> terms = tokenize(text);
        
        Map<String, Integer> freqs = new HashMap<>();
        for (String term : terms) {
            freqs.put(term, freqs.getOrDefault(term, 0) + 1);
        }
        
        docTermFreqs.add(freqs);
        docLengths.add(terms.size());
        
        for (String term : freqs.keySet()) {
            invertedIndex.computeIfAbsent(term, k -> new ArrayList<>()).add(index);
        }
        
        // Update average length if adding incrementally (approximate)
        if (avgDocLength > 0) {
             long totalLength = (long) (avgDocLength * (documents.size() - 1)) + terms.size();
             avgDocLength = (double) totalLength / documents.size();
        }
    }

    /**
     * 简单分词 (按空格和标点)
     * 支持中文分词需要引入更复杂的库 (如 Jieba, HanLP)，这里使用正则简单处理
     */
    private List<String> tokenize(String text) {
        // 匹配中文、英文单词、数字
        List<String> tokens = new ArrayList<>();
        // 正则：匹配中文字符 或 英文单词/数字
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]|[a-zA-Z0-9]+");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /**
     * 执行混合检索 (Hybrid Search)
     */
    public List<KnowledgeDocument> search(String query, int topK) {
        if (documents.isEmpty()) return Collections.emptyList();
        
        // 1. 向量检索
        List<Document> vectorResults = Collections.emptyList();
        if (vectorStore != null) {
            try {
                vectorResults = vectorStore.similaritySearch(
                    SearchRequest.query(query).withTopK(topK)
                );
            } catch (Exception e) {
                log.debug("Vector search failed (using BM25 only): {}", e.getMessage());
            }
        }
        
        // 2. BM25 检索
        List<Integer> bm25Indices = searchBM25(query, topK);
        
        // 3. 结果融合与重排序
        return mergeAndRerank(vectorResults, bm25Indices, topK);
    }
    
    /**
     * 搜索相关片段（返回切片后的文本，而不是完整文档）
     * 用于 RAG 上下文构建，避免 Token 超限
     */
    public List<String> searchSnippets(String query, int topK) {
        // 1. 优先使用向量检索获取精确片段
        if (vectorStore != null) {
            try {
                List<Document> vectorResults = vectorStore.similaritySearch(
                    SearchRequest.query(query).withTopK(topK)
                );
                
                if (!vectorResults.isEmpty()) {
                    log.info("Found {} snippets via Vector Search", vectorResults.size());
                    return vectorResults.stream()
                            .map(doc -> {
                                 String title = (String) doc.getMetadata().getOrDefault("title", "");
                                 String content = sanitizeRagText(doc.getContent());
                                 String formatted = title.isEmpty() ? content : "【" + title + "】\n" + content;
                                 return formatted;
                            })
                            .filter(s -> s != null && !s.trim().isEmpty())
                            .collect(Collectors.toList());
                }
            } catch (Exception e) {
                log.warn("Vector search failed: {}", e.getMessage());
            }
        }
        
        // 2. 如果向量检索无果，回退到混合检索（但截断内容）
        log.info("Vector search empty/failed, falling back to document search");
        List<KnowledgeDocument> docs = search(query, topK);
        return docs.stream()
                .map(doc -> {
                    String content = doc.getContent();
                    String title = doc.getTitle();
                    String combined = sanitizeRagText("【" + title + "】\n" + content);
                    if (combined.length() > 800) {
                        return combined.substring(0, 800) + "... (truncated)";
                    }
                    return combined;
                })
                .filter(s -> s != null && !s.trim().isEmpty())
                .collect(Collectors.toList());
    }

    private String sanitizeRagText(String text) {
        if (text == null) return "";
        String[] lines = text.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        boolean prevBlank = false;
        for (String line : lines) {
            String normalized = line.replace('\u00A0', ' ');
            String trimmed = normalized.trim();
            boolean isPageNum = trimmed.matches("^\\d+\\s*/\\s*\\d+$");
            boolean isDashBanner = trimmed.matches("^—{2,}.*—{2,}$");
            boolean containsCopyright = trimmed.contains("禁止用于商业用途") || trimmed.contains("违者必究");
            boolean isHeader = trimmed.contains("阿里巴巴") && trimmed.contains("Java") && trimmed.contains("开发手册");
            if (isPageNum || isDashBanner || containsCopyright || isHeader) {
                continue;
            }
            if (trimmed.isEmpty()) {
                if (!prevBlank) {
                    sb.append('\n');
                    prevBlank = true;
                }
            } else {
                sb.append(normalized).append('\n');
                prevBlank = false;
            }
        }
        return sb.toString().trim();
    }

    private List<Integer> searchBM25(String query, int topK) {
        List<String> queryTerms = tokenize(query.toLowerCase());
        Map<Integer, Double> scores = new HashMap<>();
        
        for (String term : queryTerms) {
            List<Integer> docIndices = invertedIndex.get(term);
            if (docIndices == null) continue;
            
            double idf = Math.log(1 + (documents.size() - docIndices.size() + 0.5) / (docIndices.size() + 0.5));
            
            for (Integer docIdx : docIndices) {
                // Check bounds
                if (docIdx >= docTermFreqs.size()) continue;

                int freq = docTermFreqs.get(docIdx).getOrDefault(term, 0);
                int docLen = docLengths.get(docIdx);
                
                double numerator = freq * (k1 + 1);
                double denominator = freq + k1 * (1 - b + b * (docLen / avgDocLength));
                
                scores.put(docIdx, scores.getOrDefault(docIdx, 0.0) + idf * numerator / denominator);
            }
        }
        
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private List<KnowledgeDocument> mergeAndRerank(List<Document> vectorDocs, List<Integer> bm25Indices, int topK) {
        Map<String, Double> rrfScores = new HashMap<>();
        int k = 60;
        
        if (vectorDocs != null) {
            for (int i = 0; i < vectorDocs.size(); i++) {
                Document doc = vectorDocs.get(i);
                // 尝试从 metadata 获取 source_doc_id，如果不存在则使用 docId
                String id = (String) doc.getMetadata().getOrDefault("source_doc_id", doc.getId());
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + 1.0 / (k + i + 1));
            }
        }
        
        for (int i = 0; i < bm25Indices.size(); i++) {
            if (bm25Indices.get(i) < documents.size()) {
                String id = documents.get(bm25Indices.get(i)).getId();
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + 1.0 / (k + i + 1));
            }
        }
        
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> findDocById(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
    
    private KnowledgeDocument findDocById(String id) {
        return documents.stream().filter(d -> d.getId().equals(id)).findFirst().orElse(null);
    }
}
