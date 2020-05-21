package com.slezevicius.bittorrent_client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A class for managing the reading and writing of downloading torrented
 * files.
 */
public class FileManager {
    private Torrent tor;
    private RandomAccessFile accessFile;
    private File saveFile;
    private byte[] bitfield;
    private Stack<Integer> haves;
    private Map<Integer, byte[]> incompletePieces;
    private Map<Integer, Integer> receivedBlockBytes;
    private boolean complete;
    private int downloaded;
    private int uploaded;
    private Logger log;

    FileManager(Torrent tor, File saveFile) {
        //The file manager is responsible for informing when the torrent was downloadec completely.
        //Also take care of storing the needed metadata of the downloaded file.
        log = LogManager.getFormatterLogger(FileManager.class);
        this.tor = tor;
        this.saveFile = saveFile;
        receivedBlockBytes = new HashMap<>();
        incompletePieces = new HashMap<>();
        bitfield = new byte[tor.getBitfieldLength()];
        haves = new Stack<>();
        downloaded = 0;
        uploaded = 0;
        if (filePreviouslyDownloaded()) {
            updateBitfield();
            log.debug("%s read a previously downloaded file with %d correct pieces", toString(), haves.size());
            checkIfComplete();
        }
        try {
            accessFile = new RandomAccessFile(saveFile, "rw");
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Could not find the file for a newly opened file");
        }
        complete = false;
        log.trace("%s initialized", toString());
    }

    /**
     * Checks whether the torrent file has been downloaded before
     * (either partially or fully) and returns a boolean indicating
     * the answer.
     * @return boolean
     */
    private boolean filePreviouslyDownloaded() {
        return saveFile.exists();
    }

    /**
     * Reads the previous save file from the previous download and updates
     * the bitfield to the previous state.
     */
    private void updateBitfield() {
        try {
            FileInputStream inFile = new FileInputStream(saveFile);
            for (int i = 0; i < tor.getPieces().length/20; i++) {
                byte[] piece = new byte[(int) tor.getPieceLength()];
                inFile.read(piece);
                if (pieceIsCorrect(piece, i)) {
                    int bitfieldIndex = i/8;
                    int bitIndex = i%8;
                    bitfield[bitfieldIndex] |= 128 >> bitIndex;
                    downloaded += tor.getPieceLength();
                    haves.push(i);
                }
            }
            inFile.close();
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }

    }

    public synchronized byte[] getBitfield() {
        return bitfield;
    }

    public synchronized void fillOutPiece(Request req) {
        int bytesAfterFirstPiece = (int) (req.block.length - (tor.getPieceLength() - req.begin));
        int numberOfPieces = 1 + (int) Math.ceil(((double) bytesAfterFirstPiece)/tor.getPieceLength());
        for (int i = 0; i < numberOfPieces; i++) {
            int bitfieldIndex = (req.index + i)/8;
            int bitIndex = (req.index + i)%8;
            if ((bitfield[bitfieldIndex] & (128 >> bitIndex)) == 0) {
                log.debug("%s does not have the requested piece at index %d", toString(), req.index);
                req.block = null;
                return;
            }
        }
        try {
            accessFile.seek(req.index*tor.getPieceLength());
            accessFile.read(req.block);
        } catch (IOException e) {
            req.block = null;
            log.error("%s received an IOException", toString());
            log.error(e.getMessage(), e);
        }
    }

    /**
     * Takes in a newly received block from the peer manager. If that block belongs to a piece
     * that has already been fully downloaded, the file manager simply ignores the block. If that block
     * belongs to a piece that has not been yet downloaded, it writes it to memory. If the the piece
     * gets filled out completely, the file manager confirms it against the SHA-1 hash provided in the
     * torrent file, and, assuming it passes the test, it writes it to file. If it does not pass the test,
     * then the peer manager is ordered to download that piece again.
     * 
     * If the piece is successfully written to file, it updates the downloaded field and also updates
     * its HAVEs list.
     * 
     * @param req: the Request object representing the downloaded block.
     */
    public synchronized void receivedPiece(Request req) {
        Integer index = req.index;
        Integer begin = req.begin;
        int length = req.block.length;
        log.debug("%s received a piece with index %d, begin %d, length %d",
            toString(), index.intValue(), begin.intValue(), length);
        while (true) {
            int bitfieldIndex = index/8;
            int bitIndex = index%8;
            boolean gotPiece = (bitfield[bitfieldIndex] & (128 >> bitIndex)) != 0;
            byte[] piece = null;
            if (!gotPiece && !incompletePieces.containsKey(index)) { //What's up with the gotPiece logic?
                incompletePieces.put(index, new byte[(int) tor.getPieceLength()]);
                receivedBlockBytes.put(index, 0);
            }
            piece = incompletePieces.get(index);
            int totalBytesSoFar = receivedBlockBytes.get(index);
            if (gotPiece || begin + length >= piece.length) {
                if (!gotPiece) {
                    receivedBlockBytes.put(index, totalBytesSoFar + piece.length - begin);
                    for (int i = begin; i < piece.length; i++) {
                        piece[i] = req.block[i - begin];
                    }
                    if (pieceIsFull(index) && pieceIsCorrect(index)) {
                        try {
                            log.debug("%s writing piece at index %d", toString(), index.intValue());
                            writeToFile(index);
                            bitfield[bitfieldIndex] |= 128 >> bitIndex;
                            incompletePieces.remove(index);
                            receivedBlockBytes.remove(index);
                            downloaded += piece.length;
                            haves.push(index);
                        } catch (IOException e) {
                            log.error("%s cannot write to file at inded %d", toString(), index);
                            log.error(e.getMessage(), e);
                            //Should I reomove the objects here too?
                        }
                    } else if (pieceIsFull(index)) {
                        log.debug("%s piece at index %d is invalid; repeating the piece", toString(), index.intValue());
                        repeatPiece(index);
                    }
                }
                req.block = Arrays.copyOfRange(req.block, piece.length - begin, req.block.length);
                length -= (piece.length - begin);
                index += 1;
                begin = 0;
                if (length == 0 || index + 1 == tor.getPieces().length) {
                    return;
                } else if (length < 0) {
                    log.fatal("%s the length is < 0. Request index %d, begin %d, length %d",
                        toString(), index.intValue(), begin.intValue(), length);
                    return;
                }
            } else {
                if (!gotPiece) {
                    receivedBlockBytes.put(index, totalBytesSoFar + length);
                    for (int i = 0; i < length; i++) {
                        piece[i + begin] = req.block[i];
                    }
                } else {
                    log.debug("%s received a block for a piece which has already been downloaded.", toString());
                }
                return;
            }
        }
    }

    /**
     * Checks if the sum of downloaded block lengths adds up to the piece length for
     * the piece with given index.
     * @param index
     * @return boolean
     */
    private boolean pieceIsFull(Integer index) {
        //Assuming that the peer manager makes sure there are no duplicates or overlaps
        if (receivedBlockBytes.get(index) >= tor.getPieceLength()) {
            return true;
        }
        return false;
    }

    /**
     * Checks if the piece at given index has the same SHA-1 hash as the one provided in
     * the torrent file.
     * @param index
     * @return boolean
     */
    private boolean pieceIsCorrect(Integer index) {
        try {
            byte[] piece = incompletePieces.get(index);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] pieceHash = md.digest(piece);
            byte[] infoHash = Arrays.copyOfRange(tor.getPieces(), index*20, (index+1)*20);
            return Arrays.equals(pieceHash, infoHash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm was not found");
        }
            
    }

    private boolean pieceIsCorrect(byte[] piece, int index) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] pieceHash = md.digest(piece);
            byte[] infoHash = Arrays.copyOfRange(tor.getPieces(), index*20, (index+1)*20);
            return Arrays.equals(pieceHash, infoHash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm was not found");
        }
            
    }

    /**
     * Writes a piece to the file.
     */
    private void writeToFile(int index) throws IOException {
        accessFile.seek(index*tor.getPieceLength());
        accessFile.write(incompletePieces.get(index));
    }

    /**
     * Order the peer manager to download the piece at given index again and
     * remove their incompletePieces and receivedBlockBytes entries.
     * @param index
     */
    private void repeatPiece(Integer index) {
        incompletePieces.remove(index);
        receivedBlockBytes.remove(index);
        tor.redownloadPiece(index);
    }

    /**
     * Checks the state of the download and updates the
     * complete variable with true if the file has been fully
     * downloaded, otherwise leaves it false.
     */
    private void checkIfComplete() {
        for (int i = 0; i < bitfield.length - 1; i++) {
            if (bitfield[i] != 255) {
                return;
            }
        }
        //The last byte b is usally not used up fully and there are n = 8 - pieceLegnth % 8 unusued bytes.
        //Since the bitfield is written from the most significant bit (from the left),
        //that means the right n bits are not used. 2^n - 1 is the value of the n bits are set to high.
        //Therefore, if the pieces in the last byte are fully downloaded, then b + 2^n - 1 = 255.
        int lastPiece = bitfield[bitfield.length - 1] + (1 <<  (8 - tor.getPieces().length)) - 1;
        if (lastPiece == 255) {
            complete = true;
        }
    }

    public synchronized boolean isComplete() {
        if (complete) {
            return true;
        } else {
            checkIfComplete();
            return complete;
        }
    }

    public synchronized int getDownloaded() {
        return downloaded;
    }

    public synchronized int getUploaded() {
        return uploaded;
    }

    public synchronized int[] getHaves() {
        int size = haves.size();
        int[] haveArr = new int[size];
        for (int i = 0; i < size; i++) {
            haveArr[i] = haves.pop();
        }
        return haveArr;
    }

    public synchronized void shutdown() {
        try {
            accessFile.close();
            log.trace("%s shut down", toString());
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return String.format("FileManager[name=%s, save=%s]", tor.getName(), saveFile.getAbsolutePath());
    }
}
