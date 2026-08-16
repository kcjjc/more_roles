-- ============================================================
-- 全新环境的数据库引导脚本(docker-entrypoint-initdb.d 用)
-- 只负责: pgvector 扩展 + 两个服务的 schema。
-- 表结构由各服务 ddl-auto=update 自动创建, 或手工执行:
--   db/chat_schema.sql  → chat_svc 下的 users/persona_fragment/conversation/message
--   db/rag_schema.sql   → rag_svc  下的 knowledge_base/document/doc_chunk/index_task
-- (doc_chunk 的 HNSW 索引 / 全文检索触发器在 rag_schema.sql 里, 生产建议手工执行)
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS chat_svc;
CREATE SCHEMA IF NOT EXISTS rag_svc;
