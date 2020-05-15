package com.slezevicius.bittorrent_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestInstance(Lifecycle.PER_CLASS)
public class PeerTestIntegration extends PeerTest {
    final int debuggerPort = 60001;
    final int peerPort = 60000;
    final byte[] pstrlen = {19};
    final byte[] pstr = {66, 105, 116, 84, 111, 114, 114, 101, 110, 116, 32, 112, 114, 111, 116, 111, 99, 111, 108};
    final byte[] reserved = {0, 0, 0, 0, 0, 0, 0, 0};
    final byte[] infoHash = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19};
    final byte[] peerId = {45, 88, 88, 48, 49, 48, 48, 45, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48};
    final byte[] bitfield = {123, 12, 1, 2, 3, 123, 92, 99, 88, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 12};
    final byte[] piece = {1,2,3,1,2,3,1,2,3,4,5,3,1,1,2,2,2,3,1,29,12,2,3,1,2,3,1,2,3,4,4,4,4,1,4,2,2,4,2,1,2,2,11,2,41,2,1,2,11,1,12};
    InetAddress ip;
    Process dummyPeerProc;
    ServerSocket debuggerServer;
    Socket debugger;
    DataInputStream debuggerIn;
    DataOutputStream debuggerOut;
    Socket sock;
    Peer peer;
    Logger log;

    /*
    Don't forget to test the detection of previously sent bitfield!!!
    */

    @BeforeAll
    void initiAll() {
        try {
            log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
            String configPath = System.getProperty("user.dir") + "/log.properties";
            FileInputStream configFile = new FileInputStream(configPath);
            LogManager.getLogManager().readConfiguration(configFile);
            ip = InetAddress.getByName("localhost");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @BeforeEach
    void init() {
        try {
            //Set up the debugger
            debuggerServer = new ServerSocket(debuggerPort, 1);
            String testPath = "./src/test/java/com/slezevicius/bittorrent_client/";
            String dummyPeerCMD = "python3 " + testPath + "MockPeer.py " + debuggerPort + " " + peerPort + " true";
            dummyPeerProc = Runtime.getRuntime().exec(dummyPeerCMD);
            debugger = debuggerServer.accept();
            debuggerIn = new DataInputStream(debugger.getInputStream());
            debuggerOut = new DataOutputStream(debugger.getOutputStream());

            TestingPeerManager peerManager = new TestingPeerManager();
            peerManager.setPeerId(new String(peerId, "US-ASCII"));
            peerManager.setInfoHash(infoHash);
            peerManager.setBitfield(bitfield);
            sock = new Socket(ip, peerPort);
            byte[] handshakeMessage = ArrayUtils.addAll(
                pstrlen, ArrayUtils.addAll(pstr, ArrayUtils.addAll(
                    reserved, ArrayUtils.addAll(
                        infoHash, peerId))));
            debuggerOut.write(handshakeMessage);
            peer = new Peer(sock);
            peer.introducePeerManager(peerManager);
            peer.start();
            byte[] resp = new byte[handshakeMessage.length];
            debuggerIn.read(resp);
            assumeTrue(debuggerIn.available() == 0);
            assumeTrue(Arrays.equals(resp, handshakeMessage));
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }

    }

    @AfterEach
    void destr() {
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
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSendChokeOrder() {
        try {
            assertTrue(peer.getAmChocking());
            peer.addOrder(new Pair<String, ArrayList<Object>>("choke", null));
            Thread.sleep(100);
            assertTrue(peer.getAmChocking());
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail("Interrupted");
        } catch (Exception e) {
            e.printStackTrace();
            fail("Got exception");
        }
    }

    @Test
    void testSendUnchokeOrder() {
        try {
            assertTrue(peer.getAmChocking());
            peer.addOrder(new Pair<String, ArrayList<Object>>("unchoke", null));
            Thread.sleep(100);
            assertFalse(peer.getAmChocking());
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail("Interrupted");
        } catch (Exception e) {
            e.printStackTrace();
            fail("Got exception");
        }
    }

    @Test
    void testSendUnchokeChokeOrder() {
        try {
            assertTrue(peer.getAmChocking());
            peer.addOrder(new Pair<String, ArrayList<Object>>("unchoke", null));
            Thread.sleep(100);
            assertFalse(peer.getAmChocking());
            peer.addOrder(new Pair<String, ArrayList<Object>>("choke", null));
            Thread.sleep(100);
            assertTrue(peer.getAmChocking());
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail("Interrupted");
        } catch (Exception e) {
            e.printStackTrace();
            fail("Got exception");
        }
    }

    @Test
    void testSendInterested() {
        try {
            assertFalse(peer.getAmInterested());
            peer.addOrder(new Pair<String, ArrayList<Object>>("interested", null));
            Thread.sleep(100);
            assertTrue(peer.getAmInterested());
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail("Interrupted");
        } catch (Exception e) {
            e.printStackTrace();
            fail("Got exception");
        }
    }

    @Test
    void testSendUninterested() {
        try {
            assertFalse(peer.getAmInterested());
            peer.addOrder(new Pair<String, ArrayList<Object>>("not interested", null));
            Thread.sleep(100);
            assertFalse(peer.getAmInterested());
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail("Interrupted");
        } catch (Exception e) {
            e.printStackTrace();
            fail("Got exception");
        }
    }

    @Test
    void testSendInterestedUninterested() {
        try {
            assertFalse(peer.getAmInterested());
            peer.addOrder(new Pair<String, ArrayList<Object>>("interested", null));
            Thread.sleep(100);
            assertTrue(peer.getAmInterested());
            peer.addOrder(new Pair<String, ArrayList<Object>>("not interested", null));
            Thread.sleep(100);
            assertFalse(peer.getAmInterested());
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail("Interrupted");
        } catch (Exception e) {
            e.printStackTrace();
            fail("Got exception");
        }
    }

    @Test
    void testSendHave() {
        try {
            int idx = 123112;
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(idx);
            peer.addOrder(new Pair<String, ArrayList<Object>>("have", arguments));
            Thread.sleep(100);
            byte[] byteIndex = new byte[4];
            byteIndex[0] = (byte) (idx >> 24);
            byteIndex[1] = (byte) (idx >> 16);
            byteIndex[2] = (byte) (idx >> 8);
            byteIndex[3] = (byte) idx;
            byte[] messageInfo = {0, 0, 0, 5, 4};
            byte[] haveMessage = ArrayUtils.addAll(messageInfo, byteIndex);
            byte[] resp = new byte[haveMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, haveMessage));
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail("Interrupted");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSendBitfield() {
        try {
            byte[] messageInfo = {0, 0, 0, (byte) (1 + bitfield.length), 5};
            byte[] bitfieldMessage = ArrayUtils.addAll(messageInfo, bitfield);
            ArrayList<Object> arguments = null;
            peer.addOrder(new Pair<String, ArrayList<Object>>("bitfield", arguments));
            Thread.sleep(100);
            byte[] resp = new byte[bitfieldMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, bitfieldMessage));
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail("Interrupted");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSendRequest() {
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
            byte[] messageInfo = {0, 0, 0, 13, 6};
            byte[] requestMessage = ArrayUtils.addAll(messageInfo, payload);
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(idx);
            arguments.add(begin);
            arguments.add(length);
            peer.addOrder(new Pair<String, ArrayList<Object>>("request", arguments));
            Thread.sleep(100);
            byte[] resp = new byte[requestMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, requestMessage));
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail("Interrupted");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSendPiece() {
        try {
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
            ArrayList<Object> arguments = new ArrayList<>();
            Request piece = new Request(idx, begin, block);
            arguments.add(piece);
            peer.addOrder(new Pair<String, ArrayList<Object>>("piece", arguments));
            Thread.sleep(100);
            byte[] resp = new byte[pieceMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, pieceMessage));
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail("Interrupted");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testSendCancel() {
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
            byte[] messageInfo = {0, 0, 0, 13, 8};
            byte[] cancelMessage = ArrayUtils.addAll(messageInfo, payload);
            ArrayList<Object> arguments = new ArrayList<>();
            arguments.add(idx);
            arguments.add(begin);
            arguments.add(length);
            peer.addOrder(new Pair<String, ArrayList<Object>>("cancel", arguments));
            Thread.sleep(100);
            byte[] resp = new byte[cancelMessage.length];
            assertTimeout(Duration.ofMillis(200), () -> {
                debuggerIn.read(resp);
            });
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(resp, cancelMessage));
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail("Interrupted");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testReceivePeerChoke() {
        try {
            assertTrue(peer.getPeerChocking());
            byte[] chokeMessage = {0, 0, 0, 1, 0};
            debuggerOut.write(chokeMessage);
            Thread.sleep(100);
            assertTrue(peer.getPeerChocking());
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field inField = cls.getDeclaredField("in");
            inField.setAccessible(true);
            DataInputStream in = (DataInputStream) inField.get(peer);
            assertEquals(in.available(), 0);
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testReceivePeerUnchoke() {
        try {
            assertTrue(peer.getPeerChocking());
            byte[] unchokeMessage = {0, 0, 0, 1, 1};
            debuggerOut.write(unchokeMessage);
            Thread.sleep(100);
            assertFalse(peer.getPeerChocking());
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field inField = cls.getDeclaredField("in");
            inField.setAccessible(true);
            DataInputStream in = (DataInputStream) inField.get(peer);
            assertEquals(in.available(), 0);
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testReceivePeerUnchokeChoke() {
        try {
            assertTrue(peer.getPeerChocking());
            byte[] unchokeMessage = {0, 0, 0, 1, 1};
            debuggerOut.write(unchokeMessage);
            Thread.sleep(100);
            assertFalse(peer.getPeerChocking());
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field inField = cls.getDeclaredField("in");
            inField.setAccessible(true);
            DataInputStream in = (DataInputStream) inField.get(peer);
            assertEquals(in.available(), 0);
            byte[] chokeMessage = {0, 0, 0, 1, 0};
            debuggerOut.write(chokeMessage);
            Thread.sleep(100);
            assertTrue(peer.getPeerChocking());
            inField.setAccessible(true);
            assertEquals(in.available(), 0);
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testReceivePeerInterested() {
        try {
            assertFalse(peer.getPeerInterested());
            byte[] interestedMessage = {0, 0, 0, 1, 2};
            debuggerOut.write(interestedMessage);
            Thread.sleep(100);
            assertTrue(peer.getPeerInterested());
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field inField = cls.getDeclaredField("in");
            inField.setAccessible(true);
            DataInputStream in = (DataInputStream) inField.get(peer);
            assertEquals(in.available(), 0);
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testReceivePeerUninterested() {
        try {
            assertFalse(peer.getPeerInterested());
            byte[] interestedMessage = {0, 0, 0, 1, 3};
            debuggerOut.write(interestedMessage);
            Thread.sleep(100);
            assertFalse(peer.getPeerInterested());
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field inField = cls.getDeclaredField("in");
            inField.setAccessible(true);
            DataInputStream in = (DataInputStream) inField.get(peer);
            assertEquals(in.available(), 0);
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testReceiveInterestedUninterested() {
        try {
            assertFalse(peer.getPeerInterested());
            byte[] interestedMessage = {0, 0, 0, 1, 2};
            debuggerOut.write(interestedMessage);
            Thread.sleep(100);
            assertTrue(peer.getPeerInterested());
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field inField = cls.getDeclaredField("in");
            inField.setAccessible(true);
            DataInputStream in = (DataInputStream) inField.get(peer);
            assertEquals(in.available(), 0);
            byte[] uninterestedMessage = {0, 0, 0, 1, 3};
            debuggerOut.write(uninterestedMessage);
            Thread.sleep(100);
            assertFalse(peer.getPeerInterested());
            assertEquals(in.available(), 0);
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
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
            byte[] infoMessage = {0, 0, 0, 5, 4};
            byte[] message = ArrayUtils.addAll(infoMessage, haveMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field peerBitfieldField = cls.getDeclaredField("peerBitfield");
            peerBitfieldField.setAccessible(true);
            byte[] peerBitfield = (byte[]) peerBitfieldField.get(peer);
            Field haveQueueField = cls.getDeclaredField("haveQueue");
            haveQueueField.setAccessible(true);
            ConcurrentLinkedQueue<Integer> haveQueue = (ConcurrentLinkedQueue<Integer>) haveQueueField.get(peer);
            assertFalse(haveQueue.contains(Integer.valueOf(idx)));
            assertTrue((peerBitfield[8] & 0x80) == 0x00);
            debuggerOut.write(message);
            Thread.sleep(100);
            assertTrue(haveQueue.contains(Integer.valueOf(idx)));
            assertTrue((peerBitfield[8] & 0x80) == 0x80);
            assertEquals(peer.getPeerHaves(), Integer.valueOf(idx));
            assertEquals(haveQueue.size(), 0);
            Field inField = cls.getDeclaredField("in");
            inField.setAccessible(true);
            DataInputStream in = (DataInputStream) inField.get(peer);
            assertEquals(in.available(), 0);
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testReceiveBitfield() {
        try {
            byte[] infoMessage = {0, 0, 0, (byte) (1 + bitfield.length), 5};
            byte[] bitfieldMessage = ArrayUtils.addAll(infoMessage, bitfield);
            debuggerOut.write(bitfieldMessage);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field peerBitfieldField = cls.getDeclaredField("peerBitfield");
            peerBitfieldField.setAccessible(true);
            byte[] peerBitfield = (byte[]) peerBitfieldField.get(peer);
            Thread.sleep(100);
            assertTrue(Arrays.equals(bitfield, peerBitfield));
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
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
            byte[] infoMessage = {0, 0, 0, 13, 6};
            byte[] requestMessage = ArrayUtils.addAll(infoMessage, payload);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field requestQueueField = cls.getDeclaredField("requestQueue");
            requestQueueField.setAccessible(true);
            ConcurrentLinkedQueue<Request> requestQueue = (ConcurrentLinkedQueue<Request>) requestQueueField.get(peer);
            assertEquals(requestQueue.size(), 0);
            debuggerOut.write(requestMessage);
            Thread.sleep(100);
            assertEquals(requestQueue.size(), 1);
            Request req = peer.getRequest();
            assertEquals(requestQueue.size(), 0);
            assertEquals(idx, req.index);
            assertEquals(begin, req.begin);
            assertEquals(length, req.block.length);
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testReceivePiece() {
        try {
            int idx = 2131231212;
            int begin = 3123;
            byte[] payload = new byte[8];
            payload[0] = (byte) (idx >> 24);
            payload[1] = (byte) (idx >> 16);
            payload[2] = (byte) (idx >> 8);
            payload[3] = (byte) idx;
            payload[4] = (byte) (begin >> 24);
            payload[5] = (byte) (begin >> 16);
            payload[6] = (byte) (begin >> 8);
            payload[7] = (byte) begin;
            byte[] infoMessage = {0, 0, 0, (byte) (9 + piece.length), 7};
            byte[] pieceMessage = ArrayUtils.addAll(infoMessage, ArrayUtils.addAll(payload, piece));
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field pieceQueueField = cls.getDeclaredField("pieceQueue");
            pieceQueueField.setAccessible(true);
            ConcurrentLinkedQueue<Request> pieceQueue = (ConcurrentLinkedQueue<Request>) pieceQueueField.get(peer);
            assertEquals(pieceQueue.size(), 0);
            debuggerOut.write(pieceMessage);
            Thread.sleep(100);
            assertEquals(pieceQueue.size(), 1);
            Request receivedPiece = peer.getNewPiece();
            assertEquals(pieceQueue.size(), 0);
            assertEquals(idx, receivedPiece.index);
            assertEquals(begin, receivedPiece.begin);
            assertTrue(Arrays.equals(receivedPiece.block, piece));
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
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
            byte[] infoMessage = {0, 0, 0, 13, 8};
            byte[] cancelMessage = ArrayUtils.addAll(infoMessage, payload);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field cancelListField = cls.getDeclaredField("cancelList");
            cancelListField.setAccessible(true);
            List<int[]> cancelList = (List<int[]>) cancelListField.get(peer);
            assertEquals(cancelList.size(), 0);
            debuggerOut.write(cancelMessage);
            Thread.sleep(100);
            assertEquals(cancelList.size(), 1);
            int[] cancel = cancelList.get(0);
            assertEquals(idx, cancel[0]);
            assertEquals(begin, cancel[1]);
            assertEquals(length, cancel[2]);
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail("Could not set up the test");
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }

    }

    @Test
    void testReceiveWrongID() {
        try {
            byte[] badMessage = {0, 0, 0, 14, 32};
            debuggerOut.write(badMessage);
            Thread.sleep(100);
            assertFalse(peer.isAlive());
            peer.close();
            peer.join(100);
            assertFalse(peer.isAlive());
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }
} 