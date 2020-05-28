# Sembucha torrent client
Sembucha is a bittorrent client that is controlled from the command line. The protocol is implemented in Java and the control interface is implemented in Python.  

# Features
* Torrent downloading and seeding
* Multiple simultaneous peer support (limited to 50 peers)
* Multiple simultaneous torrent support
* Resumption from previous downloads (can pause/start downloads as needed)
* Rarest first piece selection strategy

# Usage
**Starting the client**
`sembucha --run-client`

**Download status**
`sembucha --status`

**Adding torrent files**
`sembucha -a file1.torrent file2.torrent`

**Removing torrent files**
`sembucha -r file1.torrent file2.torrent`

**List the added files**
`sembucha -l`

**Change torrent file status to downloading**
`sembucha -s file1.torrent file2.torrent`

**Change torrent file status to stopped**
`sembucha -p file1.torrent file2.torrent`  

Note: Torrent files can be manipulated by `sembucha` even when the client is running.  

# Installation
Run `sh install.sh`  
It should work on all unix-like systems. A python script gets added to `~/.local/bin` and a directory to `~/.local/share` which contains a jar file, metainfo files, and the currently added torrent files (their copies). Add `~/.local/bin` to your PATH in order to use sembucha from the command line.

# Lacking features
* DHT, LTEP or any other bittorrent extension is not implemented. However, the structure of the program is easily extensible to incorporate them.
* Magnet links
* End game strategy

# Caution
This bittorrent client is experimental and is not meant to be a substitute for any of the established bittorrent clients. I am still not confident in calling it stable and it has only been tested for around a week with ~40 downloads with 3 different torrent files. It is fairly fast at downloading with a significant slowdown for the last pieces (which would be solved by an end game strategy).  

The written tests are not going to pass as I have changed the structure of the program midway in the project and I did not bother to update the tests.