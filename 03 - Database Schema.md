# Database Schema

## groups
```sql
CREATE TABLE groups (
id INTEGER PRIMARY KEY AUTOINCREMENT,
name TEXT UNIQUE NOT NULL,
color TEXT DEFAULT '#4CAF50',
created_at INTEGER NOT NULL
);
```

## branches
```sql
CREATE TABLE branches (
id INTEGER PRIMARY KEY AUTOINCREMENT,
group_id INTEGER NOT NULL,
name TEXT NOT NULL,
path TEXT UNIQUE NOT NULL,
parent_branch_id INTEGER,
created_at INTEGER NOT NULL,
FOREIGN KEY(group_id) REFERENCES groups(id)
);
```

## nodes
```sql
CREATE TABLE nodes (
id INTEGER PRIMARY KEY AUTOINCREMENT,
branch_id INTEGER NOT NULL,
content TEXT NOT NULL,
status TEXT DEFAULT 'active', -- active, completed, trashed
confidence REAL DEFAULT 1.0,
source TEXT,
speaker_label TEXT,
created_at INTEGER NOT NULL,
updated_at INTEGER NOT NULL,
last_accessed INTEGER NOT NULL,
FOREIGN KEY(branch_id) REFERENCES branches(id)
);
```

## edges
```sql
CREATE TABLE edges (
id INTEGER PRIMARY KEY AUTOINCREMENT,
source_node_id INTEGER NOT NULL,
target_node_id INTEGER NOT NULL,
relation TEXT NOT NULL, -- depends_on, sub_task_of, blocks, related_to, contradicts
strength REAL DEFAULT 0.8,
created_at INTEGER NOT NULL,
FOREIGN KEY(source_node_id) REFERENCES nodes(id),
FOREIGN KEY(target_node_id) REFERENCES nodes(id)
);
```

## FTS4 Virtual Table
```sql
CREATE VIRTUAL TABLE nodes_fts USING fts4(
content,
content='nodes'
);
```

## node_embeddings (Local Vector DB)
```sql
CREATE TABLE node_embeddings (
node_id INTEGER PRIMARY KEY,
embedding BLOB, -- FloatArray converted to ByteArray
FOREIGN KEY(node_id) REFERENCES nodes(id) ON DELETE CASCADE
);
```

## transcripts (The Vault)
```sql
CREATE TABLE transcripts (
id INTEGER PRIMARY KEY AUTOINCREMENT,
text TEXT NOT NULL,
speaker TEXT NOT NULL,
created_at INTEGER NOT NULL
);
```

## memory_queue (Offline Fallback)
```sql
CREATE TABLE memory_queue (
id INTEGER PRIMARY KEY AUTOINCREMENT,
raw_text TEXT,
audio_blob BLOB,
speaker_label TEXT,
source TEXT DEFAULT 'ambient',
status TEXT DEFAULT 'pending', -- pending, failed, completed
created_at INTEGER NOT NULL
);
```

## chat_messages
```sql
CREATE TABLE chat_messages (
id INTEGER PRIMARY KEY AUTOINCREMENT,
day_timestamp INTEGER NOT NULL,
role TEXT NOT NULL,
content TEXT NOT NULL,
is_nudge INTEGER DEFAULT 0,
thinking_trace TEXT,
model_used TEXT,
created_at INTEGER NOT NULL
);
```
