---
name: room-migrations
description: Checklist for any SQL/schema change in this project.
---
Version bump -> idempotent migration -> recreate triggers -> rebuild FTS (INSERT INTO fts(fts) VALUES('rebuild')) -> golden fixture of the previous version -> fresh-install smoke test calling every Storage method -> check SQLite NULL ordering (NULLs first on ASC) -> indexes for new queries (raw_message(day_key), reminder(fire_at)). Dev phones that ran squashed schemas must reinstall. Never INSERT OR REPLACE a node.
