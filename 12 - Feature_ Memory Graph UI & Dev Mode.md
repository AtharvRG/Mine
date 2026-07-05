# Feature: Memory Graph UI & Dev Mode

## The Tabbed Interface
The app uses a clean Bottom Navigation Bar with 4 tabs:
1. **Database:** Graph Editor and Chronos controls.
2. **Chat:** The Daily Canvas and Orb UI.
3. **Ambient:** Service toggles and Transcript Vault.
4. **Settings:** Personas, Location, and Voice Enrollment.

## Graph Editor
An interactive list of all nodes in the database.
- Color-coded by status (Active = Gray, Completed = Green, Trashed = Red).
- Users can manually add nodes (which routes through the ToolExecutor to ensure Vector Embeddings are generated).
- Users can tap nodes to Edit text, Complete, or Trash them.

## The Transcript Vault
A dedicated screen showing every raw sentence Whisper heard that day.
- Includes an **"Extract to Graph"** button on every card.
- If the Curator missed something, the user taps the button to force the AI to re-process that specific transcript.

