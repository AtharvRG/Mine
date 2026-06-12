# Feature: Memory Graph UI & Dev Mode

## Memory Map (Read-Only Viewer)

- Tree layout: Groups as root nodes, expandable branches
- Node cards: Tap to view full content, status, source, date
- Edge lines: Curved connections between related nodes
- Search bar: FTS5 query filters visible tree
- Color coding by group

## Dev Mode Editor

Unlocked via Settings -> Dev Mode ON.

Capabilities:
- Add manual nodes to any branch
- Edit node content, status, branch assignment
- Delete nodes with confirmation dialog
- Force-create branches and groups
- View raw edges between nodes
- View raw queue states (memory_queue, api_queue)
- View API call log (last 50 calls with latency)

## Graph Editor Screen Layout

```mermaid
flowchart TD
    A[Graph Editor Screen] --> B[Top Bar: Search + Filter]
    A --> C[Tree View: Groups<br/>Expandable Branches]
    A --> D[Node Card: Tap to Edit]
    A --> E[Fab: Add Node]
    
    D --> F[Bottom Sheet Edit]
    F --> G[Content Field]
    F --> H[Status Dropdown]
    F --> I[Branch Selector]
    F --> J[Delete Button]
```

## Pruning Strategy

Automated nightly WorkManager task:

```mermaid
flowchart TD
    A[Nightly Pruning Worker] --> B[Find completed nodes<br/>older than 30 days]
    B --> C[Compress into<br/>weekly summary node]
    C --> D[Mark originals<br/>archived or delete]
    D --> E[Delete rejected_logs<br/>older than 7 days]
    E --> F[Vacuum Database]
```

## Screen Inventory Related to Graph

- MemoryMapScreen: Read-only tree viewer
- GraphEditorScreen: Dev mode CRUD interface
- NodeDetailScreen: Single node view with incoming/outgoing edges