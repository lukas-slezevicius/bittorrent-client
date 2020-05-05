package com.slezevicius.bittorrent_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.InvalidParameterException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;


@TestInstance(Lifecycle.PER_CLASS)
public class PeerTestPair extends PeerTest {
    final int debuggerPort = 60001;
    final int peerPort = 60000;
    final byte[] pstrlen = {19};
    final byte[] pstr = {66, 105, 116, 84, 111, 114, 114, 101, 110, 116, 32, 112, 114, 111, 116, 111, 99, 111, 108};
    final byte[] reserved = {0, 0, 0, 0, 0, 0, 0, 0};
    final byte[] infoHash = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19};
    final byte[] peerId = {45, 88, 88, 48, 49, 48, 48, 45, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48};
    final byte[] bitfield = {123, 12, 1, 2, 3, 123, 92, 99, 88, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 12};
    InetAddress ip;
    Process dummyPeerProc;
    ServerSocket debuggerServer;
    Socket debugger;
    DataInputStream debuggerIn;
    DataOutputStream debuggerOut;
    Logger log;
    Peer peer;

    @BeforeAll
    void initiAll() {
        try {
            //Set up the logger
            log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
            String configPath = System.getProperty("user.dir") + "/log.properties";
            FileInputStream configFile = new FileInputStream(configPath);
            LogManager.getLogManager().readConfiguration(configFile);

            //Set up the debugger
            ip = InetAddress.getByName("localhost");
            debuggerServer = new ServerSocket(debuggerPort, 1);
            String testPath = "./src/test/java/com/slezevicius/bittorrent_client/";
            String dummyPeerCMD = "python3 " + testPath + "MockPeer.py " + debuggerPort + " " + peerPort;
            dummyPeerProc = Runtime.getRuntime().exec(dummyPeerCMD);
            debugger = debuggerServer.accept();
            debuggerIn = new DataInputStream(debugger.getInputStream());
            debuggerOut = new DataOutputStream(debugger.getOutputStream());
        } catch (UnknownHostException e) {
            fail(e.getMessage());
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

    @BeforeEach
    void init() {
        try {
            Pair<InetAddress, Integer> pair = new Pair<>(ip, peerPort);
            TestingPeerManager peerManager = new TestingPeerManager();
            peerManager.setPeerId(new String(peerId, "US-ASCII"));
            peerManager.setInfoHash(infoHash);
            peerManager.setBitfield(bitfield);
            peer = new Peer(pair, peerManager);
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getClass() + ": " + e.getCause());
        }
    }

    @AfterEach
    void destr() {
        peer.shutdownSockets();
    }

    @AfterAll
    void destrAll() {
        try {
            String killCMD = "kill -s SIGINT " + dummyPeerProc.pid();
            Process killProc = Runtime.getRuntime().exec(killCMD);
            try {
                killProc.waitFor(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
            debuggerServer.close();
            debugger.close();
            debuggerIn.close();
            dummyPeerProc.destroy();
        } catch (IOException e) {
            log.warning("Could not close the echoReceiver");
        }
    }

    @Test
    void testSendHandshake() {
        byte[] handshakeMessage = ArrayUtils.addAll(
            pstrlen, ArrayUtils.addAll(pstr, ArrayUtils.addAll(
                reserved, ArrayUtils.addAll(
                    infoHash, peerId))));
        try {
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("sendHandshake");
            method.setAccessible(true);
            method.invoke(peer);
            byte[] resp = new byte[68];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, handshakeMessage));
        } catch (ClassNotFoundException e) {
            fail("ClassNotFound: Peer");
        } catch (NoSuchMethodException e) {
            fail("No such method: sendHandshake");
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        } catch (IllegalAccessException e) {
            fail("Illegal access exception");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testReceiveHandshakeCorrect() {
        try {
            byte[] handshakeMessage = ArrayUtils.addAll(
                pstrlen, ArrayUtils.addAll(pstr, ArrayUtils.addAll(
                    reserved, ArrayUtils.addAll(
                        infoHash, peerId))));
            debuggerOut.write(handshakeMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("receiveHandshake");
            method.setAccessible(true);
            method.invoke(peer);
            assertTrue(Arrays.equals(peer.getInfoHash(), infoHash));
            assertFalse(peer.getLTEP());
            assertFalse(peer.getDHT());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        }
    }

    @Test
    void testReceiveHandshakeWrongPstrlen() {
        try {
            byte[] pstrlen = {18};
            byte[] handshakeMessage = ArrayUtils.addAll(
                pstrlen, ArrayUtils.addAll(pstr, ArrayUtils.addAll(
                    reserved, ArrayUtils.addAll(
                        infoHash, peerId))));
            debuggerOut.write(handshakeMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("receiveHandshake");
            method.setAccessible(true);
            method.invoke(peer);
            fail("Method did not raise DataFormatException");
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            assertEquals(e.getCause().getClass(), DataFormatException.class);
        }
    }

    @Test
    void testReceiveHandshakeWrongPstr() {
        try {
            byte[] pstr = {66, 104, 116, 84, 111, 114, 114, 101, 110, 116, 32, 112, 114, 111, 116, 111, 99, 111, 108};
            byte[] handshakeMessage = ArrayUtils.addAll(
                pstrlen, ArrayUtils.addAll(pstr, ArrayUtils.addAll(
                    reserved, ArrayUtils.addAll(
                        infoHash, peerId))));
            debuggerOut.write(handshakeMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("receiveHandshake");
            method.setAccessible(true);
            method.invoke(peer);
            fail("Method did not raise DataFormatException");
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            assertEquals(e.getCause().getClass(), DataFormatException.class);
        }
    }

    @Test
    void testReceiveHandshakeShortMessage() {
        try {
            byte[] pstr = {66, 105, 116, 84, 111, 114, 114, 101, 110, 116, 32, 112, 114, 111, 116};
            byte[] handshakeMessage = ArrayUtils.addAll(pstrlen, pstr);
            debuggerOut.write(handshakeMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("receiveHandshake");
            method.setAccessible(true);
            method.invoke(peer);
            fail("Method did not raise EOFException");
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            assertEquals(e.getCause().getClass(), EOFException.class);
        }
    }

    @Test
    void testReceiveHandshakeLtep() {
        try {
            byte[] reserved = {0, 0, 0, 0, 0, 0x10, 0, 0};
            byte[] handshakeMessage = ArrayUtils.addAll(
                pstrlen, ArrayUtils.addAll(pstr, ArrayUtils.addAll(
                    reserved, ArrayUtils.addAll(
                        infoHash, peerId))));
            debuggerOut.write(handshakeMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("receiveHandshake");
            method.setAccessible(true);
            method.invoke(peer);
            assertTrue(Arrays.equals(peer.getInfoHash(), infoHash));
            assertTrue(peer.getLTEP());
            assertFalse(peer.getDHT());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        }
    }

    @Test
    void testReceiveHandshakeDht() {
        try {
            byte[] reserved = {0, 0, 0, 0, 0, 0, 0, 0x01};
            byte[] handshakeMessage = ArrayUtils.addAll(
                pstrlen, ArrayUtils.addAll(pstr, ArrayUtils.addAll(
                    reserved, ArrayUtils.addAll(
                        infoHash, peerId))));
            debuggerOut.write(handshakeMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("receiveHandshake");
            method.setAccessible(true);
            method.invoke(peer);
            assertTrue(Arrays.equals(peer.getInfoHash(), infoHash));
            assertFalse(peer.getLTEP());
            assertTrue(peer.getDHT());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        }
    }

    @Test
    void testReceiveHandshakeWrongInfoHash() {
        try {
            byte[] infoHash = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,99};
            byte[] handshakeMessage = ArrayUtils.addAll(
                pstrlen, ArrayUtils.addAll(pstr, ArrayUtils.addAll(
                    reserved, ArrayUtils.addAll(
                        infoHash, peerId))));
            debuggerOut.write(handshakeMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("receiveHandshake");
            method.setAccessible(true);
            method.invoke(peer);
            fail("Method did not raise SecurityException");
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            assertEquals(e.getCause().getClass(), SecurityException.class);
        }
    }

    @Test
    void testReceiveHandshakeTimeout() {
        try {
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("receiveHandshake");
            method.setAccessible(true);
            method.invoke(peer);
            fail("Method did not raise IOException");
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            fail("Could not set up the test");
        } catch (InvocationTargetException e) {
            assertEquals(e.getCause().getClass(), IOException.class);
        }
    }

    @Test
    void testSendChoke() {
        byte[] chokeMessage = {0, 0, 0, 1, 0};
        try {
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("choke");
            method.setAccessible(true);
            method.invoke(peer);
            byte[] resp = new byte[chokeMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, chokeMessage));
        } catch (ClassNotFoundException e) {
            fail("ClassNotFound: Peer");
        } catch (NoSuchMethodException e) {
            fail("No such method: choke");
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        } catch (IllegalAccessException e) {
            fail("Illegal access exception");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSendUnchoke() {
        byte[] unchokeMessage = {0, 0, 0, 1, 1};
        try {
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("unchoke");
            method.setAccessible(true);
            method.invoke(peer);
            byte[] resp = new byte[unchokeMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, unchokeMessage));
        } catch (ClassNotFoundException e) {
            fail("ClassNotFound: Peer");
        } catch (NoSuchMethodException e) {
            fail("No such method: unchoke");
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        } catch (IllegalAccessException e) {
            fail("Illegal access exception");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSendInterested() {
        byte[] interestedMessage = {0, 0, 0, 1, 2};
        try {
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("interested");
            method.setAccessible(true);
            method.invoke(peer);
            byte[] resp = new byte[interestedMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, interestedMessage));
        } catch (ClassNotFoundException e) {
            fail("ClassNotFound: Peer");
        } catch (NoSuchMethodException e) {
            fail("No such method: interested");
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        } catch (IllegalAccessException e) {
            fail("Illegal access exception");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSendUninterested() {
        byte[] uninterestedMessage = {0, 0, 0, 1, 3};
        try {
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("uninterested");
            method.setAccessible(true);
            method.invoke(peer);
            byte[] resp = new byte[uninterestedMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, uninterestedMessage));
        } catch (ClassNotFoundException e) {
            fail("ClassNotFound: Peer");
        } catch (NoSuchMethodException e) {
            fail("No such method: uninterested");
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        } catch (IllegalAccessException e) {
            fail("Illegal access exception");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSendHave() {
        int idx = 2131231212;
        byte[] byteIndex = new byte[4];
        byteIndex[0] = (byte) (idx >> 24);
        byteIndex[1] = (byte) (idx >> 16);
        byteIndex[2] = (byte) (idx >> 8);
        byteIndex[3] = (byte) idx;
        byte[] messageInfo = {0, 0, 0, 5, 4};
        byte[] haveMessage = ArrayUtils.addAll(messageInfo, byteIndex);
        try {
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("have", ArrayList.class);
            method.setAccessible(true);
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(idx);
            method.invoke(peer, arguments);
            byte[] resp = new byte[haveMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, haveMessage));
        } catch (ClassNotFoundException e) {
            fail("ClassNotFound: Peer");
        } catch (NoSuchMethodException e) {
            fail("No such method: have");
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        } catch (IllegalAccessException e) {
            fail("Illegal access exception");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSendBitfield() {
        byte[] messageInfo = {0, 0, 0, (byte) (1 + bitfield.length), 5};
        byte[] bitfieldMessage = ArrayUtils.addAll(messageInfo, bitfield);
        try {
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("bitfield");
            method.setAccessible(true);
            method.invoke(peer);
            byte[] resp = new byte[bitfieldMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, bitfieldMessage));
        } catch (ClassNotFoundException e) {
            fail("ClassNotFound: Peer");
        } catch (NoSuchMethodException e) {
            fail("No such method: bitfield");
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        } catch (IllegalAccessException e) {
            fail("Illegal access exception");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSendRequest() {
        int idx = 2131231212;
        int begin = 3123;
        int length = 16384;
        byte[] payload = new byte[12];
        payload[0] = (byte) (idx >> 24);
        payload[1] = (byte) (idx >> 16);
        payload[2] = (byte) (idx >> 8);
        payload[3] = (byte) idx;
        payload[4] = (byte) (begin >> 24);
        payload[5] = (byte) (begin >> 16);
        payload[6] = (byte) (begin >> 8);
        payload[7] = (byte) begin;
        payload[8] = (byte) (length >> 24);
        payload[9] = (byte) (length >> 16);
        payload[10] = (byte) (length >> 8);
        payload[11] = (byte) length;
        byte[] messageInfo = {0, 0, 0, 13, 6};
        byte[] requestMessage = ArrayUtils.addAll(messageInfo, payload);
        try {
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("request", ArrayList.class);
            method.setAccessible(true);
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(idx);
            arguments.add(begin);
            arguments.add(length);
            method.invoke(peer, arguments);
            byte[] resp = new byte[requestMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, requestMessage));
        } catch (ClassNotFoundException e) {
            fail("ClassNotFound: Peer");
        } catch (NoSuchMethodException e) {
            fail("No such method: request");
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        } catch (IllegalAccessException e) {
            fail("Illegal access exception");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSendPiece() {
        int idx = 21312312;
        int begin = 312;
        byte[] idxBeginBytes = new byte[8];
        idxBeginBytes[0] = (byte) (idx >> 24);
        idxBeginBytes[1] = (byte) (idx >> 16);
        idxBeginBytes[2] = (byte) (idx >> 8);
        idxBeginBytes[3] = (byte) idx;
        idxBeginBytes[4] = (byte) (begin >> 24);
        idxBeginBytes[5] = (byte) (begin >> 16);
        idxBeginBytes[6] = (byte) (begin >> 8);
        idxBeginBytes[7] = (byte) begin;
        byte[] block = {123, 123, 123, 22, 22, 11, 12, 1, 13, 42, 85, 92, 12, 42, 94, 23};
        byte[] payload = ArrayUtils.addAll(idxBeginBytes, block);
        byte[] messageInfo = {0, 0, 0, (byte) (9 + block.length), 7};
        byte[] pieceMessage = ArrayUtils.addAll(messageInfo, payload);
        try {
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("piece", ArrayList.class);
            method.setAccessible(true);
            ArrayList<Object> arguments = new ArrayList<>();
            Request piece = new Request(idx, begin, block);
            arguments.add(piece);
            method.invoke(peer, arguments);
            byte[] resp = new byte[pieceMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, pieceMessage));
        } catch (ClassNotFoundException e) {
            fail("ClassNotFound: Peer");
        } catch (NoSuchMethodException e) {
            fail("No such method: piece");
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        } catch (IllegalAccessException e) {
            fail("Illegal access exception");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSendCancel() {
        int idx = 2131231212;
        int begin = 3123;
        int length = 16384;
        byte[] payload = new byte[12];
        payload[0] = (byte) (idx >> 24);
        payload[1] = (byte) (idx >> 16);
        payload[2] = (byte) (idx >> 8);
        payload[3] = (byte) idx;
        payload[4] = (byte) (begin >> 24);
        payload[5] = (byte) (begin >> 16);
        payload[6] = (byte) (begin >> 8);
        payload[7] = (byte) begin;
        payload[8] = (byte) (length >> 24);
        payload[9] = (byte) (length >> 16);
        payload[10] = (byte) (length >> 8);
        payload[11] = (byte) length;
        byte[] messageInfo = {0, 0, 0, 13, 8};
        byte[] cancelMessage = ArrayUtils.addAll(messageInfo, payload);
        try {
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("cancel", ArrayList.class);
            method.setAccessible(true);
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(idx);
            arguments.add(begin);
            arguments.add(length);
            method.invoke(peer, arguments);
            byte[] resp = new byte[cancelMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, cancelMessage));
        } catch (ClassNotFoundException e) {
            fail("ClassNotFound: Peer");
        } catch (NoSuchMethodException e) {
            fail("No such method: cancel");
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        } catch (IllegalAccessException e) {
            fail("Illegal access exception");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }

    }

    @Test
    void testIntToUInt32() {
        try {
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("intToUInt32", long.class);
            method.setAccessible(true);
            int n = 0;
            byte[] correct = new byte[4];
            correct[0] = (byte) (n >> 24);
            correct[1] = (byte) (n >> 16);
            correct[2] = (byte) (n >> 8);
            correct[3] = (byte) n;
            assertTrue(Arrays.equals(correct, (byte[]) method.invoke(null, n)));
            n = 13123;
            correct = new byte[4];
            correct[0] = (byte) (n >> 24);
            correct[1] = (byte) (n >> 16);
            correct[2] = (byte) (n >> 8);
            correct[3] = (byte) n;
            assertTrue(Arrays.equals(correct, (byte[]) method.invoke(null, n)));
            long k = 4294967295L;
            correct = new byte[4];
            correct[0] = (byte) (k >> 24);
            correct[1] = (byte) (k >> 16);
            correct[2] = (byte) (k >> 8);
            correct[3] = (byte) k;
            assertTrue(Arrays.equals(correct, (byte[]) method.invoke(null, k)));
        } catch (ClassNotFoundException e) {
            fail("ClassNotFound: Peer");
        } catch (NoSuchMethodException e) {
            fail("No such method: intToUInt32");
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        } catch (IllegalAccessException e) {
            fail("Illegal access exception");
        }
    }

    @Test
    void testIntToUInt32TooLarge() {
        try {
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("intToUInt32", long.class);
            method.setAccessible(true);
            long n = 4294967296L;
            byte[] correct = new byte[4];
            correct[0] = (byte) (n >> 24);
            correct[1] = (byte) (n >> 16);
            correct[2] = (byte) (n >> 8);
            correct[3] = (byte) n;
            byte[] resp = (byte[]) method.invoke(null, n);
            fail("Was expecting an InvalidParameterException");
        } catch (ClassNotFoundException e) {
            fail("ClassNotFound: Peer");
        } catch (NoSuchMethodException e) {
            fail("No such method: intToUInt32");
        } catch (InvocationTargetException e) {
            assertEquals(e.getCause().getClass(), InvalidParameterException.class);
        } catch (IllegalAccessException e) {
            fail("Illegal access exception");
        }
    }

    @Test
    void testReceiveHave() {
        try {
            int idx = 64;
            byte[] haveMessage = new byte[4];
            haveMessage[0] = (byte) (idx >> 24);
            haveMessage[1] = (byte) (idx >> 16);
            haveMessage[2] = (byte) (idx >> 8);
            haveMessage[3] = (byte) idx;
            debuggerOut.write(haveMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field peerBitfieldField = cls.getDeclaredField("peerBitfield");
            peerBitfieldField.setAccessible(true);
            byte[] peerBitfield = (byte[]) peerBitfieldField.get(peer);
            Field haveQueueField = cls.getDeclaredField("haveQueue");
            haveQueueField.setAccessible(true);
            ConcurrentLinkedQueue<Integer> haveQueue = (ConcurrentLinkedQueue<Integer>) haveQueueField.get(peer);
            Method method = cls.getDeclaredMethod("receiveHave");
            method.setAccessible(true);
            assertFalse(haveQueue.contains(Integer.valueOf(idx)));
            assertTrue((peerBitfield[8] & 0x80) == 0x00);
            method.invoke(peer);
            assertTrue(haveQueue.contains(Integer.valueOf(idx)));
            assertTrue((peerBitfield[8] & 0x80) == 0x80);
            assertEquals(peer.getPeerHaves(), Integer.valueOf(idx));
            assertEquals(haveQueue.size(), 0);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        }
    }

    @Test
    void testReceiveBitfield() {
        try {
            debuggerOut.write(bitfield);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field peerBitfieldField = cls.getDeclaredField("peerBitfield");
            peerBitfieldField.setAccessible(true);
            byte[] peerBitfield = (byte[]) peerBitfieldField.get(peer);
            Method method = cls.getDeclaredMethod("receiveBitfield", int.class);
            method.setAccessible(true);
            method.invoke(peer, bitfield.length);
            assertTrue(Arrays.equals(bitfield, peerBitfield));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        }
    }

    @Test
    void testReceiveBitfieldWrongLength() {
        try {
            byte[] bitfield = ArrayUtils.addAll(this.bitfield, new byte[2]);
            debuggerOut.write(bitfield);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field peerBitfieldField = cls.getDeclaredField("peerBitfield");
            peerBitfieldField.setAccessible(true);
            byte[] peerBitfield = (byte[]) peerBitfieldField.get(peer);
            Method method = cls.getDeclaredMethod("receiveBitfield", int.class);
            method.setAccessible(true);
            method.invoke(peer, bitfield.length);
            fail("Did not throw SecurityException");
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            assertEquals(e.getCause().getClass(), SecurityException.class);
        }
    }

    @Test
    void testReceiveRequest() {
        try {
            int idx = 2131231212;
            int begin = 3123;
            int length = 16384;
            byte[] payload = new byte[12];
            payload[0] = (byte) (idx >> 24);
            payload[1] = (byte) (idx >> 16);
            payload[2] = (byte) (idx >> 8);
            payload[3] = (byte) idx;
            payload[4] = (byte) (begin >> 24);
            payload[5] = (byte) (begin >> 16);
            payload[6] = (byte) (begin >> 8);
            payload[7] = (byte) begin;
            payload[8] = (byte) (length >> 24);
            payload[9] = (byte) (length >> 16);
            payload[10] = (byte) (length >> 8);
            payload[11] = (byte) length;
            byte[] requestMessage = payload;
            debuggerOut.write(requestMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field requestQueueField = cls.getDeclaredField("requestQueue");
            requestQueueField.setAccessible(true);
            ConcurrentLinkedQueue<Request> requestQueue = (ConcurrentLinkedQueue<Request>) requestQueueField.get(peer);
            Method method = cls.getDeclaredMethod("receiveRequest");
            method.setAccessible(true);
            assertEquals(requestQueue.size(), 0);
            method.invoke(peer);
            assertEquals(requestQueue.size(), 1);
            Request req = peer.getRequest();
            assertEquals(requestQueue.size(), 0);
            assertEquals(idx, req.index);
            assertEquals(begin, req.begin);
            assertEquals(length, req.block.length);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        }
    }

    @Test
    void testReceiveRequestTooLong() {
        try {
            int idx = 2131231212;
            int begin = 3123;
            int length = 32769;
            byte[] payload = new byte[12];
            payload[0] = (byte) (idx >> 24);
            payload[1] = (byte) (idx >> 16);
            payload[2] = (byte) (idx >> 8);
            payload[3] = (byte) idx;
            payload[4] = (byte) (begin >> 24);
            payload[5] = (byte) (begin >> 16);
            payload[6] = (byte) (begin >> 8);
            payload[7] = (byte) begin;
            payload[8] = (byte) (length >> 24);
            payload[9] = (byte) (length >> 16);
            payload[10] = (byte) (length >> 8);
            payload[11] = (byte) length;
            byte[] requestMessage = payload;
            debuggerOut.write(requestMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field requestQueueField = cls.getDeclaredField("requestQueue");
            requestQueueField.setAccessible(true);
            ConcurrentLinkedQueue<Request> requestQueue = (ConcurrentLinkedQueue<Request>) requestQueueField.get(peer);
            Method method = cls.getDeclaredMethod("receiveRequest");
            method.setAccessible(true);
            assertEquals(requestQueue.size(), 0);
            method.invoke(peer);
            assertEquals(requestQueue.size(), 0);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        }
    }

    @Test
    void testReceivePiece() {
        try {
            int idx = 2131231212;
            int begin = 3123;
            int length = 32769;
            byte[] payload = new byte[12];
            payload[0] = (byte) (idx >> 24);
            payload[1] = (byte) (idx >> 16);
            payload[2] = (byte) (idx >> 8);
            payload[3] = (byte) idx;
            payload[4] = (byte) (begin >> 24);
            payload[5] = (byte) (begin >> 16);
            payload[6] = (byte) (begin >> 8);
            payload[7] = (byte) begin;
            payload[8] = (byte) (length >> 24);
            payload[9] = (byte) (length >> 16);
            payload[10] = (byte) (length >> 8);
            payload[11] = (byte) length;
            byte[] requestMessage = payload;
            debuggerOut.write(requestMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field requestQueueField = cls.getDeclaredField("requestQueue");
            requestQueueField.setAccessible(true);
            ConcurrentLinkedQueue<Request> requestQueue = (ConcurrentLinkedQueue<Request>) requestQueueField.get(peer);
            Method method = cls.getDeclaredMethod("receiveRequest");
            method.setAccessible(true);
            assertEquals(requestQueue.size(), 0);
            method.invoke(peer);
            assertEquals(requestQueue.size(), 0);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        }

    }

    @Test
    void testReceiveCancel() {
        try {
            int idx = 2131231212;
            int begin = 3123;
            int length = 16384;
            byte[] payload = new byte[12];
            payload[0] = (byte) (idx >> 24);
            payload[1] = (byte) (idx >> 16);
            payload[2] = (byte) (idx >> 8);
            payload[3] = (byte) idx;
            payload[4] = (byte) (begin >> 24);
            payload[5] = (byte) (begin >> 16);
            payload[6] = (byte) (begin >> 8);
            payload[7] = (byte) begin;
            payload[8] = (byte) (length >> 24);
            payload[9] = (byte) (length >> 16);
            payload[10] = (byte) (length >> 8);
            payload[11] = (byte) length;
            byte[] cancelMessage = payload;
            debuggerOut.write(cancelMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field cancelListField = cls.getDeclaredField("cancelList");
            cancelListField.setAccessible(true);
            List<int[]> cancelList = (List<int[]>) cancelListField.get(peer);
            Method method = cls.getDeclaredMethod("receiveCancel");
            method.setAccessible(true);
            assertEquals(cancelList.size(), 0);
            method.invoke(peer);
            assertEquals(cancelList.size(), 1);
            int[] cancel = cancelList.get(0);
            assertEquals(idx, cancel[0]);
            assertEquals(begin, cancel[1]);
            assertEquals(length, cancel[2]);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getClass() + ": " + e.getCause().getMessage());
        }
    }

    @Test
    void testReceiveExtension() {
    }
}