package com.xd.xdminiclaw.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 文档索引服务
 *
 * 应用启动时自动扫描 classpath:/rag/ 目录下所有支持的文档（.txt / .md），
 * 切分后向量化并写入 pgVector（source=rag），支持增量更新（按文件名+内容 hash 去重）。
 *
 * 与长期记忆（source=long_term_memory）隔离，通过 metadata 过滤区分。
 */
@Slf4j
@Service
public class RagIndexingService implements ApplicationRunner {

    /** RAG 文档的 metadata 标记，用于与长期记忆区分 */
    public static final String META_SOURCE     = "source";
    public static final String META_SOURCE_VAL = "rag";
    public static final String META_FILE_NAME  = "fileName";
    public static final String META_HASH       = "contentHash";

    private static final int CHUNK_SIZE        = 800;   // token 数
    private static final int CHUNK_OVERLAP     = 100;

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter;

    public RagIndexingService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.splitter = new TokenTextSplitter(CHUNK_SIZE, CHUNK_OVERLAP, 5, 10000, true);
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[RAG] 开始扫描 classpath:/rag/ 目录...");
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:/rag/*.{txt,md}");

            if (resources.length == 0) {
                log.info("[RAG] 未找到任何文档，跳过索引。");
                return;
            }

            int indexed = 0;
            for (Resource resource : resources) {
                try {
                    indexed += indexResource(resource);
                } catch (Exception e) {
                    log.warn("[RAG] 索引文件 {} 失败（已跳过）: {}", resource.getFilename(), e.getMessage());
                }
            }
            log.info("[RAG] 索引完成，共处理 {} 个文件，新写入 {} 个分块", resources.length, indexed);

        } catch (IOException e) {
            log.error("[RAG] 扫描 classpath:/rag/ 失败: {}", e.getMessage());
        }
    }

    /**
     * 对单个文件增量索引：若该文件内容未变则跳过，否则先删除旧版再写入新版。
     *
     * @return 写入的分块数量（0 表示无需更新）
     */
    private int indexResource(Resource resource) throws IOException {
        String fileName = resource.getFilename();
        String content  = resource.getContentAsString(StandardCharsets.UTF_8);
        String hash     = Integer.toHexString(content.hashCode());

        // 检查是否已有相同 hash 的版本
        if (alreadyIndexed(fileName, hash)) {
            log.debug("[RAG] 文件 {} 无变化，跳过", fileName);
            return 0;
        }

        // 删除该文件旧版本（fileName 相同但 hash 不同的条目）
        deleteByFileName(fileName);

        // 构建 Document 列表
        Document raw = new Document(content, Map.of(
                META_SOURCE,    META_SOURCE_VAL,
                META_FILE_NAME, fileName,
                META_HASH,      hash
        ));

        // 切分 + 写入
        List<Document> chunks = splitter.apply(List.of(raw));
        // 把公共 metadata 复制到每个切片（splitter 不自动继承）
        List<Document> enriched = new ArrayList<>();
        for (Document chunk : chunks) {
            Map<String, Object> meta = new java.util.HashMap<>(chunk.getMetadata());
            meta.put(META_SOURCE,    META_SOURCE_VAL);
            meta.put(META_FILE_NAME, fileName);
            meta.put(META_HASH,      hash);
            enriched.add(new Document(chunk.getText(), meta));
        }

        vectorStore.add(enriched);
        log.info("[RAG] 文件 {} 已索引，切分为 {} 个分块", fileName, enriched.size());
        return enriched.size();
    }

    /**
     * 判断该文件（相同 fileName + hash）是否已在向量库中。
     */
    private boolean alreadyIndexed(String fileName, String hash) {
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression filter = b.and(
                    b.eq(META_SOURCE,    META_SOURCE_VAL),
                    b.and(
                            b.eq(META_FILE_NAME, fileName),
                            b.eq(META_HASH,      hash)
                    )
            ).build();

            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(fileName)
                            .topK(1)
                            .filterExpression(filter)
                            .build()
            );
            return !docs.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 删除指定文件名的所有旧分块（用于更新时清理）。
     */
    private void deleteByFileName(String fileName) {
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression filter = b.and(
                    b.eq(META_SOURCE,    META_SOURCE_VAL),
                    b.eq(META_FILE_NAME, fileName)
            ).build();
            vectorStore.delete(filter);
            log.debug("[RAG] 已删除文件 {} 的旧索引", fileName);
        } catch (Exception e) {
            log.warn("[RAG] 删除旧索引失败（将继续写入新版）: {}", e.getMessage());
        }
    }

    /**
     * 供外部调用：按语义检索 RAG 知识库（仅返回 source=rag 的文档）。
     */
    public List<Document> search(String query, int topK) {
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression filter = b.eq(META_SOURCE, META_SOURCE_VAL).build();

            return vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .filterExpression(filter)
                            .build()
            );
        } catch (Exception e) {
            log.warn("[RAG] 检索失败: {}", e.getMessage());
            return List.of();
        }
    }
}
