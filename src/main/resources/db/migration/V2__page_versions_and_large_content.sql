-- Preserve V1 for existing installations. CLOB avoids driver VARCHAR size limits.
ALTER TABLE wiki_pages ALTER COLUMN content CLOB;
UPDATE wiki_pages SET content = '' WHERE content IS NULL;
ALTER TABLE wiki_pages ALTER COLUMN content SET NOT NULL;
ALTER TABLE wiki_pages ALTER COLUMN path VARCHAR(16384);
UPDATE wiki_pages SET path = '/' || path WHERE path NOT LIKE '/%';
ALTER TABLE wiki_pages ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
