package com.slezevicius.bittorrent_client;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.zip.DataFormatException;

public class Metainfo {
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
    private byte[] infoHash;

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
        if (data instanceof LinkedHashMap) {
            //The data linkedhashmap is guaranteed to be of type signature <String, Object> by Bencoding
            LinkedHashMap<String, Object> metaDict = (LinkedHashMap<String, Object>) data;
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

    private void updateInfo(LinkedHashMap<String, Object> metaDict) throws DataFormatException {
        String keyName = "info";
        if (metaDict.containsKey(keyName)) {
            //The infoDict is guaranteed to be of type signature <String, Object> by Bencoding
            LinkedHashMap<String, Object> infoDict = (LinkedHashMap<String, Object>) metaDict.get(keyName);
            updateInfoHash(infoDict);
            updatePieceLength(infoDict);
            updatePieces(infoDict);
            //Supports only single file mode
            updateName(infoDict);
            updateLength(infoDict);
        } else {
            throw new DataFormatException("Metainfo dict must contain the " + keyName + " key");
        }
    }

    private void updateInfoHash(LinkedHashMap<String, Object> infoDict) throws DataFormatException {
        ArrayList<Byte> ByteList = Bencoding.encode(infoDict);
        Byte[] ByteArray = new Byte[ByteList.size()];
        ByteArray = ByteList.toArray(ByteArray);
        byte[] byteArray = new byte[ByteArray.length];
        for (int i = 0; i < ByteArray.length; i++) {
            byteArray[i] = ByteArray[i].byteValue();
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        infoHash = md.digest(byteArray);
    }

    private void updatePieceLength(LinkedHashMap<String, Object> infoDict) throws DataFormatException {
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

    private void updatePieces(LinkedHashMap<String, Object> infoDict) throws DataFormatException {
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

    private void updateName(LinkedHashMap<String, Object> infoDict) throws DataFormatException {
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

    private void updateLength(LinkedHashMap<String, Object> infoDict) throws DataFormatException {
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

    private void updateAnnounce(LinkedHashMap<String, Object> metaDict) throws DataFormatException {
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

    private void updateAnnounceList(LinkedHashMap<String, Object> metaDict) throws DataFormatException {
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

    private void updateCreationDate(LinkedHashMap<String, Object> metaDict) throws DataFormatException {
        String keyName = "creation date";
        if (metaDict.containsKey(keyName)) {
            if (metaDict.get(keyName) instanceof Long) {
                creationDate = (long) metaDict.get(keyName);
            } else {
                throw new DataFormatException("The value of key " + keyName + " must be of type int.");
            }
        }
    }

    private void updateComment(LinkedHashMap<String, Object> metaDict) throws DataFormatException {
        String keyName = "comment";
        if (metaDict.containsKey(keyName)) {
            if (metaDict.get(keyName) instanceof byte[]) {
                comment = new String((byte[]) metaDict.get(keyName));
            } else {
                throw new DataFormatException("The value of key " + keyName + " must be of type byte string.");
            }
        }
    }

    private void updateCreatedBy(LinkedHashMap<String, Object> metaDict) throws DataFormatException {
        String keyName = "created by";
        if (metaDict.containsKey(keyName)) {
            if (metaDict.get(keyName) instanceof byte[]) {
                createdBy = new String((byte[]) metaDict.get(keyName));
            } else {
                throw new DataFormatException("The value of key " + keyName + " must be of type byte string.");
            }
        }
    }

    private void updateEncoding(LinkedHashMap<String, Object> metaDict) throws DataFormatException {
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

    public String getAnnounce() {
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

    private static final byte[] HEX_ARRAY = "0123456789ABCDEF".getBytes();

    public static String bytesToHex(byte[] bytes) {
        byte[] hexChars = new byte[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars, StandardCharsets.UTF_8);
    }

    public String getHexInfoHash() {
        return Metainfo.bytesToHex(infoHash);
    }

    public byte[] getInfoHash() {
        return infoHash;
    }

    @Override
    public String toString() {
        //Reimplement this
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