-- 仅在数据卷首次初始化（空目录）时执行。
-- 之后由 Flyway V1__init.sql 负责结构与扩展的版本化迁移。
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
