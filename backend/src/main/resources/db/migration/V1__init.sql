CREATE TABLE sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    display_name VARCHAR(64),
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tag (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE segment (
    id BIGSERIAL PRIMARY KEY,
    source_text TEXT NOT NULL,
    translated_text TEXT NOT NULL,
    work_title VARCHAR(255),
    chapter VARCHAR(255),
    author VARCHAR(255),
    dynasty VARCHAR(64),
    translator VARCHAR(255),
    notes TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
    version INT NOT NULL DEFAULT 0,
    content_hash VARCHAR(64) NOT NULL,
    created_by BIGINT NOT NULL REFERENCES sys_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_segment_content_hash ON segment (content_hash);
CREATE INDEX idx_segment_dynasty ON segment (dynasty);
CREATE INDEX idx_segment_work_title ON segment (work_title);
CREATE INDEX idx_segment_updated_at ON segment (updated_at);
CREATE INDEX idx_segment_status ON segment (status);

CREATE TABLE segment_tag (
    segment_id BIGINT NOT NULL REFERENCES segment(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
    PRIMARY KEY (segment_id, tag_id)
);
