# Autonomous Execution Protocols
- If any terminal command, build script, or file write fails more than twice with the same or similar error, STOP immediately.
- Never attempt to fix a failing error by running the exact same execution chain without modifying the file system first.
- If you hit a conceptual wall where a test suite fails 3 consecutive times, output a summary of the bottleneck and pause for user intervention. Do not burn turns guessing.
