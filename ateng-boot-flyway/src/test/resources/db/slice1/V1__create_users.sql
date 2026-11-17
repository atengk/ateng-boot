CREATE TABLE IF NOT EXISTS sample_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL
);
INSERT INTO sample_user (id, username) VALUES (1, 'ateng');
