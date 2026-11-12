-- Files are publicly readable, but all mutations authenticate against their owning page.
CREATE TABLE wiki_files (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE,
    page_path VARCHAR(16384) NOT NULL,
    relative_path VARCHAR(4096) NOT NULL,
    storage_key VARCHAR(20000) NOT NULL UNIQUE,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT wiki_files_page_fk FOREIGN KEY (page_path) REFERENCES wiki_pages(path),
    CONSTRAINT wiki_files_page_relative UNIQUE (page_path, relative_path)
);
-- The unique (page_path, relative_path) index also supports page-scoped listing and the FK.
