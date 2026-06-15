package com.trade.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bm25IndexService 单元测试
 *
 * 测试重点：
 * 1. 添加和搜索 chunk
 * 2. 中文分词搜索
 * 3. 搜索结果排序
 * 4. 空查询处理
 * 5. 索引清空
 */
class Bm25IndexServiceTest {

    private Bm25IndexService bm25IndexService;

    @BeforeEach
    void setUp() throws Exception {
        bm25IndexService = new Bm25IndexService();
        bm25IndexService.init();
    }

    @Test
    void testAddAndSearchChunk() {
        // 添加测试数据
        bm25IndexService.addChunk("doc1", 0, "LED面板灯产品介绍");
        bm25IndexService.addChunk("doc1", 1, "产品规格参数");
        bm25IndexService.addChunk("doc2", 0, "光伏逆变器技术手册");

        // 提交索引
        bm25IndexService.commit();

        // 搜索测试
        List<Bm25IndexService.Bm25Match> results = bm25IndexService.search("LED面板灯", 10);

        // 验证结果
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).documentId()).isEqualTo("doc1");
        assertThat(results.get(0).chunkIndex()).isEqualTo(0);
        assertThat(results.get(0).content()).contains("LED面板灯");
    }

    @Test
    void testChineseSearch() {
        // 添加中文测试数据
        bm25IndexService.addChunk("doc1", 0, "产品出厂附带全套出口清关资质：欧盟CE认证、TUV并网认证");
        bm25IndexService.addChunk("doc1", 1, "CQC国内光伏认证、RoHS环保检测报告");
        bm25IndexService.commit();

        // 搜索中文关键词
        List<Bm25IndexService.Bm25Match> results = bm25IndexService.search("CE认证", 10);

        // 验证结果
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).content()).contains("CE认证");
    }

    @Test
    void testSearchRanking() {
        // 添加测试数据
        bm25IndexService.addChunk("doc1", 0, "LED面板灯 LED面板灯 LED面板灯");
        bm25IndexService.addChunk("doc2", 0, "LED面板灯");
        bm25IndexService.commit();

        // 搜索测试
        List<Bm25IndexService.Bm25Match> results = bm25IndexService.search("LED面板灯", 10);

        // 验证排序：包含更多关键词的文档应该排在前面
        assertThat(results).hasSize(2);
        assertThat(results.get(0).documentId()).isEqualTo("doc1"); // 包含3次关键词
        assertThat(results.get(1).documentId()).isEqualTo("doc2"); // 包含1次关键词
    }

    @Test
    void testEmptyQuery() {
        // 添加测试数据
        bm25IndexService.addChunk("doc1", 0, "测试内容");
        bm25IndexService.commit();

        // 空查询
        List<Bm25IndexService.Bm25Match> results = bm25IndexService.search("", 10);

        // 验证结果为空
        assertThat(results).isEmpty();
    }

    @Test
    void testClearIndex() {
        // 添加测试数据
        bm25IndexService.addChunk("doc1", 0, "测试内容");
        bm25IndexService.commit();

        // 验证数据已添加
        assertThat(bm25IndexService.getDocumentCount()).isEqualTo(1);

        // 清空索引
        bm25IndexService.clear();

        // 验证索引已清空
        assertThat(bm25IndexService.getDocumentCount()).isEqualTo(0);

        // 搜索应返回空结果
        List<Bm25IndexService.Bm25Match> results = bm25IndexService.search("测试", 10);
        assertThat(results).isEmpty();
    }

    @Test
    void testSearchWithLimit() {
        // 添加多个测试数据
        for (int i = 0; i < 10; i++) {
            bm25IndexService.addChunk("doc" + i, 0, "LED面板灯产品介绍 " + i);
        }
        bm25IndexService.commit();

        // 限制搜索结果数量
        List<Bm25IndexService.Bm25Match> results = bm25IndexService.search("LED面板灯", 3);

        // 验证结果数量不超过限制
        assertThat(results).hasSize(3);
    }

    @Test
    void testAddEmptyChunk() {
        // 添加空 chunk
        bm25IndexService.addChunk("doc1", 0, "");
        bm25IndexService.addChunk("doc1", 1, "   ");
        bm25IndexService.addChunk("doc1", 2, "有效内容");
        bm25IndexService.commit();

        // 验证只有有效内容被索引
        assertThat(bm25IndexService.getDocumentCount()).isEqualTo(1);

        List<Bm25IndexService.Bm25Match> results = bm25IndexService.search("有效内容", 10);
        assertThat(results).hasSize(1);
    }
}
