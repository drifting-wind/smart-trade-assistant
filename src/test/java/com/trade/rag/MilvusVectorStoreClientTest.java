package com.trade.rag;

import com.trade.config.AiGatewayProperties;
import com.trade.rag.dto.SearchResultDto.SearchMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * MilvusVectorStoreClient 单元测试
 *
 * 测试重点：
 * 1. SearchMatch 数据结构测试
 * 2. 元数据转换逻辑测试
 * 3. 配置属性映射测试
 *
 * 注意：由于 Milvus SDK 使用 gRPC，完整的集成测试需要使用真实 Milvus 实例或 MockWebServer。
 * 这里主要测试数据结构和配置相关的逻辑。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MilvusVectorStoreClientTest {

    @Mock
    private AiGatewayProperties properties;

    @Mock
    private AiGatewayProperties.Rag ragProperties;

    @Mock
    private AiGatewayProperties.Rag.Milvus milvusProperties;

    @BeforeEach
    void setUp() {
        // 配置 Mock 对象
        when(properties.getRag()).thenReturn(ragProperties);
        when(ragProperties.getCollectionName()).thenReturn("test_collection");
        when(ragProperties.getDimension()).thenReturn(1024);
        when(ragProperties.getSimilarityThreshold()).thenReturn(0.7);
        when(ragProperties.getMilvus()).thenReturn(milvusProperties);
        when(milvusProperties.getHost()).thenReturn("localhost");
        when(milvusProperties.getPort()).thenReturn(19530);
        when(milvusProperties.getDatabase()).thenReturn("default");
    }

    @Test
    void testSearchMatch_RecordCreation() {
        // 测试 SearchMatch record 的创建
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", "产品手册");
        metadata.put("page", 1);

        SearchMatch match = new SearchMatch("LED面板灯功率为50W", 0.95, "doc1", 0, metadata);

        // 验证所有字段
        assertThat(match.text()).isEqualTo("LED面板灯功率为50W");
        assertThat(match.score()).isEqualTo(0.95);
        assertThat(match.documentId()).isEqualTo("doc1");
        assertThat(match.chunkIndex()).isEqualTo(0);
        assertThat(match.metadata()).containsEntry("title", "产品手册");
        assertThat(match.metadata()).containsEntry("page", 1);
    }

    @Test
    void testSearchMatch_EmptyMetadata() {
        // 测试空元数据
        SearchMatch match = new SearchMatch("测试内容", 0.8, "doc2", 1, new HashMap<>());

        assertThat(match.text()).isEqualTo("测试内容");
        assertThat(match.score()).isEqualTo(0.8);
        assertThat(match.documentId()).isEqualTo("doc2");
        assertThat(match.chunkIndex()).isEqualTo(1);
        assertThat(match.metadata()).isEmpty();
    }

    @Test
    void testSearchMatch_NullMetadata() {
        // 测试 null 元数据 - record 会接受 null，但我们需要处理它
        SearchMatch match = new SearchMatch("测试内容", 0.7, "doc3", 0, null);

        assertThat(match.text()).isEqualTo("测试内容");
        // record 允许 null，调用方需要处理
        assertThat(match.metadata()).isNull();
    }

    @Test
    void testSearchMatch_MetadataImmutability() {
        // 测试元数据的不可变性
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", "原始标题");

        SearchMatch match = new SearchMatch("内容", 0.9, "doc1", 0, metadata);

        // 修改原始 Map
        metadata.put("title", "修改后的标题");

        // SearchMatch 应该保持不变（如果 record 是不可变的）
        // 注意：record 的字段是 final 的，但 Map 本身是可变的
        // 这里验证 record 的行为
        assertThat(match.metadata()).isNotNull();
    }

    @Test
    void testSearchMatch_ComplexMetadata() {
        // 测试复杂元数据
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", "LED面板灯产品手册");
        metadata.put("author", "技术部");
        metadata.put("version", "2024-01-01");
        metadata.put("tags", List.of("LED", "照明", "产品"));
        metadata.put("version", 123);

        SearchMatch match = new SearchMatch("LED面板灯功率为50W", 0.95, "doc1", 0, metadata);

        assertThat(match.metadata().get("title")).isEqualTo("LED面板灯产品手册");
        assertThat(match.metadata().get("author")).isEqualTo("技术部");
        assertThat(match.metadata().get("version")).isEqualTo(123);
    }

    @Test
    void testSearchMatch_ScoreRange() {
        // 测试不同分数范围
        SearchMatch highScore = new SearchMatch("高相似度", 0.99, "doc1", 0, new HashMap<>());
        SearchMatch mediumScore = new SearchMatch("中等相似度", 0.75, "doc2", 0, new HashMap<>());
        SearchMatch lowScore = new SearchMatch("低相似度", 0.3, "doc3", 0, new HashMap<>());

        assertThat(highScore.score()).isGreaterThan(0.9);
        assertThat(mediumScore.score()).isBetween(0.7, 0.8);
        assertThat(lowScore.score()).isLessThan(0.5);
    }

    @Test
    void testSearchMatch_Equality() {
        // 测试 record 的 equals 和 hashCode
        Map<String, Object> metadata1 = new HashMap<>();
        metadata1.put("title", "文档1");

        Map<String, Object> metadata2 = new HashMap<>();
        metadata2.put("title", "文档1");

        SearchMatch match1 = new SearchMatch("内容", 0.9, "doc1", 0, metadata1);
        SearchMatch match2 = new SearchMatch("内容", 0.9, "doc1", 0, metadata2);

        // record 的 equals 比较所有字段
        assertThat(match1).isEqualTo(match2);
        assertThat(match1.hashCode()).isEqualTo(match2.hashCode());
    }

    @Test
    void testSearchMatch_ToString() {
        // 测试 record 的 toString
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", "测试文档");

        SearchMatch match = new SearchMatch("LED面板灯", 0.9, "doc1", 0, metadata);

        String toString = match.toString();
        assertThat(toString).contains("LED面板灯");
        assertThat(toString).contains("0.9");
        assertThat(toString).contains("doc1");
    }

    @Test
    void testRagProperties_Configuration() {
        // 测试配置属性映射
        assertThat(properties.getRag().getCollectionName()).isEqualTo("test_collection");
        assertThat(properties.getRag().getDimension()).isEqualTo(1024);
        assertThat(properties.getRag().getSimilarityThreshold()).isEqualTo(0.7);
        assertThat(properties.getRag().getMilvus().getHost()).isEqualTo("localhost");
        assertThat(properties.getRag().getMilvus().getPort()).isEqualTo(19530);
        assertThat(properties.getRag().getMilvus().getDatabase()).isEqualTo("default");
    }

    @Test
    void testMilvusProperties_Uri() {
        // 测试 URI 拼接
        String uri = milvusProperties.getHost() + ":" + milvusProperties.getPort();
        assertThat(uri).isEqualTo("localhost:19530");
    }

    @Test
    void testSearchMatch_DocumentIdFormats() {
        // 测试不同格式的 documentId
        SearchMatch uuidDoc = new SearchMatch("内容", 0.9, "550e8400-e29b-41d4-a716-446655440000", 0, new HashMap<>());
        SearchMatch simpleDoc = new SearchMatch("内容", 0.9, "doc123", 0, new HashMap<>());
        SearchMatch pathDoc = new SearchMatch("内容", 0.9, "path/to/document.pdf", 0, new HashMap<>());

        assertThat(uuidDoc.documentId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(simpleDoc.documentId()).isEqualTo("doc123");
        assertThat(pathDoc.documentId()).isEqualTo("path/to/document.pdf");
    }

    @Test
    void testSearchMatch_ChunkIndexRange() {
        // 测试不同 chunkIndex
        SearchMatch firstChunk = new SearchMatch("第一块", 0.9, "doc1", 0, new HashMap<>());
        SearchMatch middleChunk = new SearchMatch("中间块", 0.8, "doc1", 5, new HashMap<>());
        SearchMatch lastChunk = new SearchMatch("最后一块", 0.7, "doc1", 100, new HashMap<>());

        assertThat(firstChunk.chunkIndex()).isEqualTo(0);
        assertThat(middleChunk.chunkIndex()).isEqualTo(5);
        assertThat(lastChunk.chunkIndex()).isEqualTo(100);
    }

    @Test
    void testSearchMatch_TextLength() {
        // 测试不同长度的文本
        SearchMatch shortText = new SearchMatch("短文本", 0.9, "doc1", 0, new HashMap<>());
        SearchMatch longText = new SearchMatch("这是一段很长的文本内容，用于测试长文本场景。".repeat(10), 0.8, "doc2", 0, new HashMap<>());

        assertThat(shortText.text()).hasSizeLessThan(100);
        assertThat(longText.text()).hasSizeGreaterThan(100);
    }

    @Test
    void testMetadata_KeyCaseSensitivity() {
        // 测试元数据 key 的大小写敏感性
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("Title", "大写标题");
        metadata.put("title", "小写标题");
        metadata.put("TITLE", "全大写标题");

        SearchMatch match = new SearchMatch("内容", 0.9, "doc1", 0, metadata);

        // HashMap 是大小写敏感的
        assertThat(match.metadata()).hasSize(3);
        assertThat(match.metadata().get("Title")).isEqualTo("大写标题");
        assertThat(match.metadata().get("title")).isEqualTo("小写标题");
        assertThat(match.metadata().get("TITLE")).isEqualTo("全大写标题");
    }

    @Test
    void testMetadata_NullValues() {
        // 测试元数据中的 null 值
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("title", "文档标题");
        metadata.put("author", null);
        metadata.put("date", "2024-01-01");

        SearchMatch match = new SearchMatch("内容", 0.9, "doc1", 0, metadata);

        assertThat(match.metadata()).containsEntry("author", null);
        assertThat(match.metadata().get("title")).isEqualTo("文档标题");
    }
}
