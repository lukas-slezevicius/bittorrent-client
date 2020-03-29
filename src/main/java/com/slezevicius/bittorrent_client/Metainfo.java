package com.slezevicius.bittorrent_client;

import java.io.File;
import java.io.FileInputStream; import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.zip.DataFormatException;

class Metainfo {
    private long pieceLength;
    private byte[] pieces;
    private String name;
    private long length;
    private String announce;
    private ArrayList<ArrayList<String>> announceList;
    private long creationDate = -1;
    private String comment;
    private String createdBy;
    private String encoding;

    Metainfo(String filepath) throws DataFormatException, IOException {
        Bencoding b = new Bencoding(readFile(filepath));
        updateFields(b.decode());
    }

    private byte[] readFile(String filepath) throws IOException {
        File file = new File(filepath);
        InputStream stream = new FileInputStream(file);
        byte[] output = stream.readAllBytes();
        stream.close();
        return output;
    }

    private void updateFields(Object data) throws DataFormatException {
        if (data instanceof HashMap) {
            //The data hashmap is guaranteed to be of type signature <String, Object> by Bencoding
            HashMap<String, Object> metaDict = (HashMap<String, Object>) data;
            updateInfo(metaDict);
            updateAnnounce(metaDict);
            updateAnnounceList(metaDict);
            updateCreationDate(metaDict);
            updateComment(metaDict);
            updateCreatedBy(metaDict);
            updateEncoding(metaDict);
        } else {
            throw new DataFormatException("Metainfo file should be a bencoded dictionary");
        }
    }

    private void updateInfo(HashMap<String, Object> metaDict) throws DataFormatException {
        String keyName = "info";
        if (metaDict.containsKey(keyName)) {
            //The infoDict is guaranteed to be of type signature <String, Object> by Bencoding
            HashMap<String, Object> infoDict = (HashMap<String, Object>) metaDict.get(keyName);
            updatePieceLength(infoDict);
            updatePieces(infoDict);
            //Supports only single file mode
            updateName(infoDict);
            updateLength(infoDict);
        } else {
            throw new DataFormatException("Metainfo dict must contain the " + keyName + " key");
        }
    }

    private void updatePieceLength(HashMap<String, Object> infoDict) throws DataFormatException {
        String keyName = "piece length";
        if (infoDict.containsKey(keyName)) {
            if (infoDict.get(keyName) instanceof Long) {
                pieceLength = (long) infoDict.get(keyName);
            } else {
                throw new DataFormatException("The value of key " + keyName + " must be of type int.");
            }
        } else {
            throw new DataFormatException("Metainfo dict must contain the " + keyName + " key");
        }
    }

    private void updatePieces(HashMap<String, Object> infoDict) throws DataFormatException {
        String keyName = "pieces";
        if (infoDict.containsKey(keyName)) {
            if (infoDict.get(keyName) instanceof byte[]) {
                pieces = (byte[]) infoDict.get(keyName);
            } else {
                throw new DataFormatException("The value of key " + keyName + " must be of type byte string.");
            }
        } else {
            throw new DataFormatException("Metainfo dict must contain the " + keyName + " key");
        }
    }

    private void updateName(HashMap<String, Object> infoDict) throws DataFormatException {
        String keyName = "name";
        if (infoDict.containsKey(keyName)) {
            if (infoDict.get(keyName) instanceof byte[]) {
                name = new String((byte[]) infoDict.get(keyName));
            } else {
                throw new DataFormatException("The value of key " + keyName + " must be of type byte string.");
            }
        } else {
            throw new DataFormatException("Metainfo dict must contain the " + keyName + " key");
        }
    }

    private void updateLength(HashMap<String, Object> infoDict) throws DataFormatException {
        String keyName = "length";
        if (infoDict.containsKey(keyName)) {
            if (infoDict.get(keyName) instanceof Long) {
                length = (long) infoDict.get(keyName);
            } else {
                throw new DataFormatException("The value of key " + keyName + " must be of type byte string.");
            }
        } else {
            throw new DataFormatException("Metainfo dict must contain the " + keyName + " key");
        }
    }

    private void updateAnnounce(HashMap<String, Object> metaDict) throws DataFormatException {
        String keyName = "announce";
        if (metaDict.containsKey(keyName)) {
            if (metaDict.get(keyName) instanceof byte[]) {
                announce = new String((byte[]) metaDict.get(keyName));
            } else {
                throw new DataFormatException("The value of key " + keyName + " must be of type byte string.");
            }
        } else {
            throw new DataFormatException("Metainfo dict must contain the " + keyName + " key");
        }
    }

    private void updateAnnounceList(HashMap<String, Object> metaDict) throws DataFormatException {
        String keyName = "announce-list";
        if (metaDict.containsKey(keyName)) {
            announceList = new ArrayList<ArrayList<String>>();
            if (metaDict.get(keyName) instanceof ArrayList) {
                //The announce-list list is guaranteed to be of type signature ArrayList<Object> by Bencoding
                for (Object tracker_list : (ArrayList<Object>) metaDict.get(keyName)) {
                    if (tracker_list instanceof ArrayList) {
                        announceList.add(new ArrayList<String>());
                        //The tracker_list is guaranteed to be of type signature ArrayList<Object> by Bencoding
                        for (Object el : (ArrayList<Object>) tracker_list) {
                            if (el instanceof byte[]) {
                                announceList.get(announceList.size() - 1).add(new String((byte[]) el));
                            } else {
                                throw new DataFormatException("All elements must be strings within the inner lists of key " + keyName);
                            }
                        }
                    } else {
                        throw new DataFormatException("The list of key " + keyName + " must contain only lists");
                    }
                }
            } else {
                throw new DataFormatException("The value of key " + keyName + " must be of type list.");
            }
        }
    }

    private void updateCreationDate(HashMap<String, Object> metaDict) throws DataFormatException {
        String keyName = "creation date";
        if (metaDict.containsKey(keyName)) {
            if (metaDict.get(keyName) instanceof Long) {
                creationDate = (long) metaDict.get(keyName);
            } else {
                throw new DataFormatException("The value of key " + keyName + " must be of type int.");
            }
        }
    }

    private void updateComment(HashMap<String, Object> metaDict) throws DataFormatException {
        String keyName = "comment";
        if (metaDict.containsKey(keyName)) {
            if (metaDict.get(keyName) instanceof byte[]) {
                comment = new String((byte[]) metaDict.get(keyName));
            } else {
                throw new DataFormatException("The value of key " + keyName + " must be of type byte string.");
            }
        }
    }

    private void updateCreatedBy(HashMap<String, Object> metaDict) throws DataFormatException {
        String keyName = "created by";
        if (metaDict.containsKey(keyName)) {
            if (metaDict.get(keyName) instanceof byte[]) {
                createdBy = new String((byte[]) metaDict.get(keyName));
            } else {
                throw new DataFormatException("The value of key " + keyName + " must be of type byte string.");
            }
        }
    }

    private void updateEncoding(HashMap<String, Object> metaDict) throws DataFormatException {
        String keyName = "encoding";
        if (metaDict.containsKey(keyName)) {
            if (metaDict.get(keyName) instanceof byte[]) {
                encoding = new String((byte[]) metaDict.get(keyName));
            } else {
                throw new DataFormatException("The value of key " + keyName + " must be of type byte string.");
            }
        }
    }

    public long getPieceLength() {
        return pieceLength;
    }

    public byte[] getPieces() {
        return pieces;
    }

    public String getName() {
        return name;
    }

    public long getLength() {
        return length;
    }

    public String announce() {
        return announce;
    }

    public ArrayList<ArrayList<String>> getAnnounceList() {
        return announceList;
    }

    public long getCreationDate() {
        return creationDate;
    }
    
    public String getComment() {
        return comment;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getEncoding() {
        return encoding;
    }

    @Override
    public String toString() {
        String fmt = "Metainfo [info->[name->%s, pieceLength->%d, Length->%d], announce->%s";
        fmt = fmt + ", announceList->%s";
        fmt = fmt + ", creationDate->%d";
        fmt = fmt + ", comment->%s";
        fmt = fmt + ", createdBy->%s";
        fmt = fmt + ", encoding->%s";
        fmt = fmt + "]";
        return String.format(fmt, name, pieceLength, length, announce, announceList,
                creationDate, comment, createdBy, encoding);
    }
}