package com.example.trade.exception;

/**
 * 向量数据库操作异常 —— Milvus 调用失败时抛出。
 *
 * 触发场景：
 * - 连接 Milvus 失败（服务未启动、网络不通）
 * - 集合操作失败（创建/删除集合）
 * - 向量插入失败（维度不匹配、字段缺失）
 * - 相似度搜索失败（集合不存在、索引未创建）
 *
 * 统一由 GlobalExceptionHandler 捕获并返回 502 Bad Gateway。
 */
public class VectorStoreException extends RuntimeException {

    public VectorStoreException(String message) {
        super(message);
    }

    public VectorStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
