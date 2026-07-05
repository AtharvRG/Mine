# Feature: Chat Interface

## Design Principle: The Daily Canvas
Infinite scrolling chats get laggy and lose context. Mine treats each day as a "blank canvas."
- At midnight, the UI clears.
- The `PruningWorker` summarizes yesterday's chat into the Graph DB and deletes the raw logs.

## CQRS Architecture
- **Read Path (Chat Agent):** Instant. Takes user input, runs a local Vector Search, injects context, and calls Kimi K2.6.
- **Write Path (Curator Agent):** Asynchronous. The Chat Agent silently delegates the chat log to the 120B Curator in a background coroutine to extract facts and update the SQLite database without blocking the UI.

## The Orb Component
- Position: Bottom center, floating above scroll content
- Idle: Soft glow animation
- Tap: Expands upward into text field + send button
- Hold: Scales up, turns accent color, records voice. Release to send.

## Markdown Support
A custom Compose extension parses `**bold**` text natively, allowing Kimi to format responses beautifully without heavy third-party libraries.
