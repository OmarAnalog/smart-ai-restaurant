CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
ALTER TABLE contextual_memories ADD COLUMN embedding public.vector(768);
-- Exact cosine search is sufficient for the small per-user memory collection.
