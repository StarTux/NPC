# NPC
*Send custom packets to create custom NPCs and more.*

## Description
Despite its name, this plugin concerns itself with sending custom packets for a variety of tasks, but the primary focus are custom entities which behave as NPCs.  A lot of effort was put into simulating proper movement around the world.

## Features
- Custom living entities, floating blocks, droped items, armor stands.
- Speech bubbles over NPC heads.
- A variety of tasks, such as walking, dancing, and more.
- Full abstraction for entity metadata.
- Player skin loading.
- Conversation trees.
- Spawn areas.

## Commands
The admin command is /npc.  Players clicking a conversation option will cause them to send /npca (NPC answer).
- `/npc spawn player [job]`
- `/npc spawn mob [type] [job]` (bukkit EntityType)
- `/npc spawn item [mateial] [job]` (bukkit Material)
- `/npc spawn marker [lifespan] [message]` (lifespan in ticks)
- `/npc spawn realplayer [name] [job]`
- `/npc list [radius]` - List NPCs. Provide radius to show NPC ids.
- `/npc clear` - Remove all NPCs.
- `/npc addchunk [area]` - Add current chunk to spawn area.
- `/npc sign` - **Experimental**: Open sign dialogue.
- `/npc uuid [name]` - Look up player UUID.
- `/npc skin [name]` - Look up player skin.
- `/npc history [id]` - Display movement history of NPC with id.
- `/npc debug` - Dump debug info.
- `/npc areaspawn [area]` - Spawn NPC for area.