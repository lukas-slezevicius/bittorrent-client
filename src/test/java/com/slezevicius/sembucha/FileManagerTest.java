package com.slezevicius.sembucha;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestInstance(Lifecycle.PER_CLASS)
public class FileManagerTest {
    private Logger log;
    private FileManager fileManager;
    private TestingTorrent tor;
    private File saveFile;
    private final int blockSize = 16384; //2^14
    private final int pieceSize = 1048576; //2^20
    private final int pieceCount = 20;

    @BeforeAll
    void initAll() {
        try {
            log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
            String configPath = System.getProperty("user.dir") + "/log.properties";
            FileInputStream configFile = new FileInputStream(configPath);
            LogManager.getLogManager().readConfiguration(configFile);
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @BeforeEach
    void init() {
        saveFile = new File("./testing.part");
        tor = new TestingTorrent();
        tor.bitfieldLength = (pieceCount/8) + 1;
        tor.pieces = new byte[pieceCount*20];
        tor.pieceLength = pieceSize;
        fileManager = new FileManager(tor, saveFile);
    }

    @AfterEach
    void destr(TestInfo testInfo) {
        if(testInfo.getTags().contains("SkipCleanup")) {
            return;
        }
        fileManager.shutdown();
    }

    @Test
    void receivedPieceTest() {
        try {
            long seed = 1231231;
            Class cls = Class.forName("com.slezevicius.sembucha.FileManager");
            Method method = cls.getDeclaredMethod("receivedPiece", Request.class);
            method.setAccessible(true);
            Random rand = new Random(seed);
            int[] blockInts = rand.ints(blockSize, 0, 256).toArray();
            byte[] block = new byte[blockSize];
            for (int i = 0; i < block.length; i++) {
                block[i] = (byte) (blockInts[i] & 0xFF);
            }
            Request req = new Request(0, 0, block);
            method.invoke(fileManager, req);
            Field incompletePiecesField = cls.getDeclaredField("incompletePieces");
            incompletePiecesField.setAccessible(true);
            Map<Integer, byte[]> incompletePieces = (Map<Integer, byte[]>) incompletePiecesField.get(fileManager);
            Field receivedBlockBytesField = cls.getDeclaredField("receivedBlockBytes");
            receivedBlockBytesField.setAccessible(true);
            Map<Integer, Integer> receivedBlockBytes = (Map<Integer, Integer>) receivedBlockBytesField.get(fileManager);
            assertEquals(1, incompletePieces.size());
            assertEquals(1, receivedBlockBytes.size());
            byte[] piece = incompletePieces.get(Integer.valueOf(0));
            assertArrayEquals(
                Arrays.copyOfRange(block, 0, pieceSize), piece);
            assertEquals(blockSize, receivedBlockBytes.get(Integer.valueOf(0)));
        } catch (NoSuchMethodException | ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void receivedPieceTwiceTest() {
        try {
            long seed = 123123;
            Class cls = Class.forName("com.slezevicius.sembucha.FileManager");
            Method method = cls.getDeclaredMethod("receivedPiece", Request.class);
            method.setAccessible(true);
            Random rand = new Random(seed);
            int[] blockints = rand.ints(blockSize, 0, 256).toArray();
            byte[] block1 = new byte[blockSize];
            for (int i = 0; i < block1.length; i++) {
                block1[i] = (byte) (blockints[i] & 0xff);
            }
            Request req1 = new Request(0, 0, block1);
            blockints = rand.ints(blockSize, 0, 256).toArray();
            byte[] block2 = new byte[blockSize];
            for (int i = 0; i < block2.length; i++) {
                block2[i] = (byte) (blockints[i] & 0xff);
            }
            Request req2 = new Request(0, blockSize, block2);
            method.invoke(fileManager, req1);
            method.invoke(fileManager, req2);
            Field incompletePiecesField = cls.getDeclaredField("incompletePieces");
            incompletePiecesField.setAccessible(true);
            Map<Integer, byte[]> incompletePieces = (Map<Integer, byte[]>) incompletePiecesField.get(fileManager);
            Field receivedBlockBytesField = cls.getDeclaredField("receivedBlockBytes");
            receivedBlockBytesField.setAccessible(true);
            Map<Integer, Integer> receivedBlockBytes = (Map<Integer, Integer>) receivedBlockBytesField.get(fileManager);
            assertEquals(1, incompletePieces.size());
            assertEquals(1, receivedBlockBytes.size());
            byte[] piece = incompletePieces.get(Integer.valueOf(0));
            assertArrayEquals(
                Arrays.copyOfRange(ArrayUtils.addAll(block1, block2), 0, pieceSize),
                piece);
            assertEquals(blockSize*2, receivedBlockBytes.get(Integer.valueOf(0)));
        } catch (NoSuchMethodException | ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void receivedPieceOverflowBarelyAlignedTest() {
        try {
            long seed = 12312;
            Class cls = Class.forName("com.slezevicius.sembucha.FileManager");
            Method method = cls.getDeclaredMethod("receivedPiece", Request.class);
            method.setAccessible(true);
            Random rand = new Random(seed);
            int[] blockInts = rand.ints(blockSize, 0, 256).toArray();
            byte[] block = new byte[blockSize];
            for (int i = 0; i < block.length; i++) {
                block[i] = (byte) (blockInts[i] & 0xFF);
            }
            Request req = new Request(0, pieceSize - blockSize, block);
            method.invoke(fileManager, req);
            Field incompletePiecesField = cls.getDeclaredField("incompletePieces");
            incompletePiecesField.setAccessible(true);
            Map<Integer, byte[]> incompletePieces = (Map<Integer, byte[]>) incompletePiecesField.get(fileManager);
            Field receivedBlockBytesField = cls.getDeclaredField("receivedBlockBytes");
            receivedBlockBytesField.setAccessible(true);
            Map<Integer, Integer> receivedBlockBytes = (Map<Integer, Integer>) receivedBlockBytesField.get(fileManager);
            assertEquals(1, incompletePieces.size());
            assertEquals(1, receivedBlockBytes.size());
            byte[] piece = incompletePieces.get(Integer.valueOf(0));
            assertEquals(pieceSize, piece.length);
            assertArrayEquals(
                block, Arrays.copyOfRange(piece, pieceSize-blockSize, pieceSize));
            assertArrayEquals(
                new byte[pieceSize-blockSize],
                Arrays.copyOfRange(piece, 0, pieceSize-blockSize));
            assertEquals(blockSize, receivedBlockBytes.get(Integer.valueOf(0)));
        } catch (NoSuchMethodException | ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void receivedPieceOverflowAlignedTest() {
        try {
            long seed = 11231;
            Class cls = Class.forName("com.slezevicius.sembucha.FileManager");
            Method method = cls.getDeclaredMethod("receivedPiece", Request.class);
            method.setAccessible(true);
            Random rand = new Random(seed);
            int[] blockInts = rand.ints(blockSize, 0, 256).toArray();
            byte[] block1 = new byte[blockSize];
            for (int i = 0; i < block1.length; i++) {
                block1[i] = (byte) (blockInts[i] & 0xFF);
            }
            Request req1 = new Request(0, pieceSize - blockSize, block1);
            blockInts = rand.ints(blockSize, 0, 256).toArray();
            byte[] block2 = new byte[blockSize];
            for (int i = 0; i < block2.length; i++) {
                block2[i] = (byte) (blockInts[i] & 0xff);
            }
            Request req2 = new Request(1, 0, block2);
            method.invoke(fileManager, req1);
            method.invoke(fileManager, req2);
            Field incompletePiecesField = cls.getDeclaredField("incompletePieces");
            incompletePiecesField.setAccessible(true);
            Map<Integer, byte[]> incompletePieces = (Map<Integer, byte[]>) incompletePiecesField.get(fileManager);
            Field receivedBlockBytesField = cls.getDeclaredField("receivedBlockBytes");
            receivedBlockBytesField.setAccessible(true);
            Map<Integer, Integer> receivedBlockBytes = (Map<Integer, Integer>) receivedBlockBytesField.get(fileManager);
            assertEquals(2, incompletePieces.size());
            assertEquals(2, receivedBlockBytes.size());
            byte[] piece1 = incompletePieces.get(Integer.valueOf(0));
            assertEquals(pieceSize, piece1.length);
            assertArrayEquals(
                block1, Arrays.copyOfRange(piece1, pieceSize-blockSize, pieceSize));
            assertArrayEquals(
                new byte[pieceSize-blockSize],
                Arrays.copyOfRange(piece1, 0, pieceSize-blockSize));
            assertEquals(blockSize, receivedBlockBytes.get(Integer.valueOf(0)));
            byte[] piece2 = incompletePieces.get(Integer.valueOf(1));
            assertEquals(pieceSize, piece2.length);
            assertArrayEquals(
                Arrays.copyOfRange(block2, 0, pieceSize), piece2);
            assertEquals(blockSize, receivedBlockBytes.get(Integer.valueOf(1)));
        } catch (NoSuchMethodException | ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void receivedPieceOverflowMisalignedTest() {
        try {
            long seed = 11231;
            Class cls = Class.forName("com.slezevicius.sembucha.FileManager");
            Method method = cls.getDeclaredMethod("receivedPiece", Request.class);
            method.setAccessible(true);
            Random rand = new Random(seed);
            int[] blockInts = rand.ints(blockSize, 0, 256).toArray();
            byte[] block1 = new byte[blockSize];
            for (int i = 0; i < block1.length; i++) {
                block1[i] = (byte) (blockInts[i] & 0xFF);
            }
            Request req1 = new Request(0, pieceSize - blockSize + 1000, block1);
            blockInts = rand.ints(blockSize, 0, 256).toArray();
            byte[] block2 = new byte[blockSize];
            for (int i = 0; i < block2.length; i++) {
                block2[i] = (byte) (blockInts[i] & 0xff);
            }
            Request req2 = new Request(1, 1000, block2);
            method.invoke(fileManager, req1);
            method.invoke(fileManager, req2);
            Field incompletePiecesField = cls.getDeclaredField("incompletePieces");
            incompletePiecesField.setAccessible(true);
            Map<Integer, byte[]> incompletePieces = (Map<Integer, byte[]>) incompletePiecesField.get(fileManager);
            Field receivedBlockBytesField = cls.getDeclaredField("receivedBlockBytes");
            receivedBlockBytesField.setAccessible(true);
            Map<Integer, Integer> receivedBlockBytes = (Map<Integer, Integer>) receivedBlockBytesField.get(fileManager);
            assertEquals(2, incompletePieces.size());
            assertEquals(2, receivedBlockBytes.size());
            byte[] piece1 = incompletePieces.get(Integer.valueOf(0));
            assertEquals(pieceSize, piece1.length);
            assertArrayEquals(
                Arrays.copyOfRange(block1, 0, blockSize - 1000),
                Arrays.copyOfRange(piece1, pieceSize-blockSize+1000, pieceSize));
            assertArrayEquals(
                new byte[pieceSize-blockSize+1000],
                Arrays.copyOfRange(piece1, 0, pieceSize-blockSize+1000));
            assertEquals(blockSize-1000, receivedBlockBytes.get(Integer.valueOf(0)));
            byte[] piece2 = incompletePieces.get(Integer.valueOf(1));
            assertEquals(pieceSize, piece2.length);
            assertArrayEquals(
                piece2,
                Arrays.copyOfRange(
                    ArrayUtils.addAll(
                        Arrays.copyOfRange(block1, blockSize-1000, blockSize), 
                        block2),
                    0, pieceSize));
            assertEquals(blockSize+1000, receivedBlockBytes.get(Integer.valueOf(1)));
        } catch (NoSuchMethodException | ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    @Tag("SkipCleanup")
    void receivedPieceFullTest() {
        try {
            long seed = 1231231;
            Class cls = Class.forName("com.slezevicius.sembucha.FileManager");
            Method method = cls.getDeclaredMethod("receivedPiece", Request.class);
            method.setAccessible(true);
            Random rand = new Random(seed);
            int[] blockInts = rand.ints(pieceSize, 0, 256).toArray();
            byte[] piece = new byte[pieceSize];
            for (int i = 0; i < piece.length; i++) {
                piece[i] = (byte) (blockInts[i] & 0xFF);
            }
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] pieceHash = md.digest(piece);
            tor.pieces = ArrayUtils.addAll(new byte[20*3], Arrays.copyOfRange(pieceHash, 0, 20*17));
            for (int i = 0; i < pieceSize;) {
                Request req = new Request(3, i, Arrays.copyOfRange(piece, i, i + blockSize));
                method.invoke(fileManager, req);
                i += blockSize;
            }
            fileManager.shutdown();
            InputStream fileStream = new FileInputStream(saveFile);
            byte[] writtenPiece = new byte[piece.length*4];
            int written = fileStream.read(writtenPiece);
            assertEquals(piece.length*4, written);
            assertArrayEquals(ArrayUtils.addAll(new byte[piece.length*3], piece), writtenPiece);
            fileStream.close();
        } catch (NoSuchMethodException | ClassNotFoundException | IllegalAccessException | IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    @Tag("SkipCleanup")
    void receivedPieceFullMisalignedTest() {
        try {
            long seed = 12312371;
            Class cls = Class.forName("com.slezevicius.sembucha.FileManager");
            Method method = cls.getDeclaredMethod("receivedPiece", Request.class);
            method.setAccessible(true);
            Random rand = new Random(seed);
            int[] blockInts = rand.ints(pieceSize+blockSize, 0, 256).toArray();
            byte[] piece = new byte[pieceSize+blockSize];
            for (int i = 0; i < piece.length; i++) {
                piece[i] = (byte) (blockInts[i] & 0xFF);
            }
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] pieceHash = md.digest(Arrays.copyOfRange(piece, 2000, piece.length-(blockSize-2000)));
            tor.pieces = ArrayUtils.addAll(new byte[20*3], Arrays.copyOfRange(pieceHash, 0, 20*17));
            Request req = new Request(2, pieceSize-2000, Arrays.copyOfRange(piece, 0, blockSize));
            method.invoke(fileManager, req); //This got added
            for (int i = 0; i < pieceSize/blockSize; i++) {
                req = new Request(3, blockSize - 2000 + i*blockSize, Arrays.copyOfRange(piece, blockSize*(i+1), (i+2)*blockSize));
                method.invoke(fileManager, req);
            }
            fileManager.shutdown();
            InputStream fileStream = new FileInputStream(saveFile);
            byte[] writtenPiece = new byte[pieceSize*4];
            int written = fileStream.read(writtenPiece);
            assertEquals(pieceSize*4, written);
            assertArrayEquals(
                ArrayUtils.addAll(new byte[pieceSize*3], Arrays.copyOfRange(piece, 2000, piece.length-(blockSize-2000))),
                writtenPiece);
            fileStream.close();
        } catch (NoSuchMethodException | ClassNotFoundException | IllegalAccessException | IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void receivedPieceFullIncorrectTest() {
        try {
            long seed = 1231231;
            Class cls = Class.forName("com.slezevicius.sembucha.FileManager");
            Method method = cls.getDeclaredMethod("receivedPiece", Request.class);
            method.setAccessible(true);
            Random rand = new Random(seed);
            int[] blockInts = rand.ints(pieceSize, 0, 256).toArray();
            byte[] piece = new byte[pieceSize];
            for (int i = 0; i < piece.length; i++) {
                piece[i] = (byte) (blockInts[i] & 0xFF);
            }
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] pieceHash = md.digest(piece);
            tor.pieces = ArrayUtils.addAll(new byte[20*3+1], Arrays.copyOfRange(pieceHash, 0, 20*17-1));
            for (int i = 0; i < pieceSize;) {
                Request req = new Request(3, i, Arrays.copyOfRange(piece, i, i + blockSize));
                method.invoke(fileManager, req);
                i += blockSize;
            }
            assertEquals(3, tor.redownloadPiece);
        } catch (NoSuchMethodException | ClassNotFoundException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    static class TestingTorrent extends Torrent {
        public int pieceLength;
        public byte[] pieces;
        public int bitfieldLength;
        public int redownloadPiece = -1;

        @Override
        public long getPieceLength() {
            return pieceLength;
        }

        @Override
        public byte[] getPieces() {
            return pieces;
        }

        @Override
        public int getBitfieldLength() {
            return bitfieldLength;
        }

        @Override
        public void redownloadPiece(int index) {
            redownloadPiece = index;
        }

    }
}