package com.slezevicius.bittorrent_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
public class PeerManagerTest {
    final int debuggerPort = 60001;
    final int peerPort = 60000;
    final private int pieceCount = 1250;
    private byte[] frequencyArray = new byte[pieceCount];
    private int[] peerHaves = {149, 124, 191, 292, 101};
    final byte[] pstrlen = {19};
    final byte[] pstr = {66, 105, 116, 84, 111, 114, 114, 101, 110, 116, 32, 112, 114, 111, 116, 111, 99, 111, 108};
    final byte[] reserved = {0, 0, 0, 0, 0, 0, 0, 0};
    final byte[] infoHash = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19};
    final byte[] peerId = {45, 88, 88, 48, 49, 48, 48, 45, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48};
    final byte[] bitfield = {123, 12, 1, 2, 3, 123, 92, 99, 88, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 12};
    final byte[] piece = {1,2,3,1,2,3,1,2,3,4,5,3,1,1,2,2,2,3,1,29,12,2,3,1,2,3,1,2,3,4,4,4,4,1,4,2,2,4,2,1,2,2,11,2,41,2,1,2,11,1,12};
    Logger log;
    PeerManager peerManager;
    TestingTorrent tor;
    TestingPeer peer;
    InetAddress ip;
    Process dummyPeerProc;
    ServerSocket debuggerServer;
    Socket debugger;
    DataInputStream debuggerIn;
    DataOutputStream debuggerOut;

    @BeforeAll
    void initAll() {
        try {
            //Set up the logger
            log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
            String configPath = System.getProperty("user.dir") + "/log.properties";
            FileInputStream configFile = new FileInputStream(configPath);
            LogManager.getLogManager().readConfiguration(configFile);
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }

    @BeforeEach
    void init() {
        tor = new TestingTorrent();
        tor.pieces = new byte[pieceCount];
        peer = new TestingPeer();
        peerManager = new PeerManager(tor);
    }

    @AfterEach
    void destr() {
        frequencyArray = new byte[pieceCount];
    }

    @Test
    void testUpdatePeers() {
        try {
            //Setting up the debugger
            ip = InetAddress.getByName("localhost");
            debuggerServer = new ServerSocket(debuggerPort, 1);
            String testPath = "./src/test/java/com/slezevicius/bittorrent_client/";
            String dummyPeerCMD = "python3 " + testPath + "MockPeer.py " + debuggerPort + " " + peerPort + " true";
            dummyPeerProc = Runtime.getRuntime().exec(dummyPeerCMD);
            debugger = debuggerServer.accept();
            debuggerIn = new DataInputStream(debugger.getInputStream());
            debuggerOut = new DataOutputStream(debugger.getOutputStream());

            Class cls = Class.forName("com.slezevicius.bittorrent_client.PeerManager");
            Field peersField = cls.getDeclaredField("peers");
            peersField.setAccessible(true);
            List<Peer> peers = (List<Peer>) peersField.get(peerManager);
            assertEquals(0, peers.size());
            //Adding 10 fake peers
            for (int i = 0; i < 10; i++) {
                Peer newPeer = new TestingPeer();
                peers.add(newPeer);
            }
            assertEquals(10, peers.size());

            List<Pair<InetAddress, Integer>> newPeers = new ArrayList<>();
            newPeers.add(new Pair<InetAddress, Integer>(InetAddress.getByName("localhost"), peerPort));
            peer.port = peerPort;
            tor.newPeers = newPeers;
            tor.infoHash = infoHash;
            tor.peerId = peerId;
            Method method = cls.getDeclaredMethod("updatePeers");
            method.setAccessible(true);
            method.invoke(peerManager);
            byte[] resp = new byte[68];
            debuggerIn.read(resp);
            byte[] handshakeMessage = ArrayUtils.addAll(
                pstrlen, ArrayUtils.addAll(pstr, ArrayUtils.addAll(
                    reserved, ArrayUtils.addAll(
                        infoHash, peerId))));
            debuggerOut.write(handshakeMessage);
            Thread.sleep(100);
            assertEquals(11, peers.size());
            String killCMD = "kill -s SIGINT " + dummyPeerProc.pid();
            Process killProc = Runtime.getRuntime().exec(killCMD);
            try {
                killProc.waitFor(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
            peerManager.shutdown();
            debuggerServer.close();
            debugger.close();
            debuggerIn.close();
            dummyPeerProc.destroy();
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException | NoSuchMethodException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        } catch (UnknownHostException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }
    
    @Test
    void testUpdatePeersNoRepeat() {
        try {
            //Setting up the debugger
            ip = InetAddress.getByName("localhost");
            debuggerServer = new ServerSocket(debuggerPort, 1);
            String testPath = "./src/test/java/com/slezevicius/bittorrent_client/";
            String dummyPeerCMD = "python3 " + testPath + "MockPeer.py " + debuggerPort + " " + peerPort + " true";
            dummyPeerProc = Runtime.getRuntime().exec(dummyPeerCMD);
            debugger = debuggerServer.accept();
            debuggerIn = new DataInputStream(debugger.getInputStream());
            debuggerOut = new DataOutputStream(debugger.getOutputStream());

            Class cls = Class.forName("com.slezevicius.bittorrent_client.PeerManager");
            Field peersField = cls.getDeclaredField("peers");
            peersField.setAccessible(true);
            List<Peer> peers = (List<Peer>) peersField.get(peerManager);
            assertEquals(0, peers.size());
            //Adding 10 fake peers
            for (int i = 0; i < 10; i++) {
                Peer newPeer = new TestingPeer();
                peers.add(newPeer);
            }
            TestingPeer newPeer = new TestingPeer();
            newPeer.port = peerPort;
            peers.add(newPeer);
            assertEquals(11, peers.size());

            List<Pair<InetAddress, Integer>> newPeers = new ArrayList<>();
            newPeers.add(new Pair<InetAddress, Integer>(InetAddress.getByName("localhost"), peerPort));
            peer.port = peerPort;
            tor.newPeers = newPeers;
            tor.infoHash = infoHash;
            tor.peerId = peerId;
            Method method = cls.getDeclaredMethod("updatePeers");
            method.setAccessible(true);
            method.invoke(peerManager);
            Thread.sleep(100);
            assertEquals(11, peers.size());
            String killCMD = "kill -s SIGINT " + dummyPeerProc.pid();
            Process killProc = Runtime.getRuntime().exec(killCMD);
            try {
                killProc.waitFor(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
            peerManager.shutdown();
            debuggerServer.close();
            debugger.close();
            debuggerIn.close();
            dummyPeerProc.destroy();
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException | NoSuchMethodException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        } catch (UnknownHostException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testUpdatePeersMax() {
        try {
            //Setting up the debugger
            ip = InetAddress.getByName("localhost");
            debuggerServer = new ServerSocket(debuggerPort, 1);
            String testPath = "./src/test/java/com/slezevicius/bittorrent_client/";
            String dummyPeerCMD = "python3 " + testPath + "MockPeer.py " + debuggerPort + " " + peerPort + " true";
            dummyPeerProc = Runtime.getRuntime().exec(dummyPeerCMD);
            debugger = debuggerServer.accept();
            debuggerIn = new DataInputStream(debugger.getInputStream());
            debuggerOut = new DataOutputStream(debugger.getOutputStream());

            Class cls = Class.forName("com.slezevicius.bittorrent_client.PeerManager");
            Field peersField = cls.getDeclaredField("peers");
            peersField.setAccessible(true);
            List<Peer> peers = (List<Peer>) peersField.get(peerManager);
            assertEquals(0, peers.size());
            //Adding 10 fake peers
            for (int i = 0; i < 50; i++) {
                Peer newPeer = new TestingPeer();
                peers.add(newPeer);
            }
            assertEquals(50, peers.size());

            List<Pair<InetAddress, Integer>> newPeers = new ArrayList<>();
            newPeers.add(new Pair<InetAddress, Integer>(InetAddress.getByName("localhost"), peerPort));
            peer.port = peerPort;
            tor.newPeers = newPeers;
            tor.infoHash = infoHash;
            tor.peerId = peerId;
            Method method = cls.getDeclaredMethod("updatePeers");
            method.setAccessible(true);
            method.invoke(peerManager);
            Thread.sleep(100);
            assertEquals(50, peers.size());
            String killCMD = "kill -s SIGINT " + dummyPeerProc.pid();
            Process killProc = Runtime.getRuntime().exec(killCMD);
            try {
                killProc.waitFor(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
            peerManager.shutdown();
            debuggerServer.close();
            debugger.close();
            debuggerIn.close();
            dummyPeerProc.destroy();
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException | NoSuchMethodException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        } catch (UnknownHostException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testUpdateHaves() {
        try {
            peer.haveQueue = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < peerHaves.length; i++) {
                peer.haveQueue.add(peerHaves[i]);
            }
            int[] haves = {1,19,124,1240,124};
            Class cls2 = Class.forName("com.slezevicius.bittorrent_client.PeerManagerTest$TestingPeer");
            Field orderQueueField = cls2.getDeclaredField("orderQueue");
            orderQueueField.setAccessible(true);
            ConcurrentLinkedQueue<Pair<String, ArrayList<Object>>> orderQueue = (ConcurrentLinkedQueue<Pair<String, ArrayList<Object>>>) orderQueueField.get(peer);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.PeerManager");
            Field frequencyArrayField = cls.getDeclaredField("frequencyArray");
            frequencyArrayField.setAccessible(true);
            frequencyArrayField.set(peerManager, frequencyArray);
            Field availablePieceListField = cls.getDeclaredField("availablePieceList");
            availablePieceListField.setAccessible(true);
            List<Integer> availablePieceList = (List<Integer>) availablePieceListField.get(peerManager);
            Method method = cls.getDeclaredMethod("updateHaves", Peer.class, int[].class);
            method.setAccessible(true);
            assertEquals(orderQueue.size(), 0);
            assertEquals(0, availablePieceList.size());
            for (int i = 0; i < peerHaves.length; i++) {
                assertEquals(frequencyArray[peerHaves[i]], 0);
                assertFalse(availablePieceList.contains(peerHaves[i]));
            }
            method.invoke(peerManager, peer, haves);
            assertEquals(orderQueue.size(), haves.length);
            assertEquals(peerHaves.length, availablePieceList.size());
            for (int i = 0; i < haves.length; i++) {
                assertEquals(frequencyArray[peerHaves[i]], 1);
                assertTrue(availablePieceList.contains(peerHaves[i]));
            }
            int i = 0;
            while (i < haves.length) {
                Pair<String, ArrayList<Object>> order = orderQueue.poll();
                if (order == null) {
                    fail("Got a null order");
                    return;
                }
                assertTrue(order.getLeft().equals("have"));
                int idx = (int) order.getRight().get(0);
                assertEquals(idx, haves[i]);
                i += 1;
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testUpdateHavesFalsePeerHaves() {
        try {
            peer.haveQueue = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < peerHaves.length; i++) {
                peer.haveQueue.add(peerHaves[i]); //All valid
            }
            peer.haveQueue.add(10101010); //Too large
            peer.haveQueue.add(-12); //Too small
            int[] haves = new int[0];
            Class cls2 = Class.forName("com.slezevicius.bittorrent_client.PeerManagerTest$TestingPeer");
            Field orderQueueField = cls2.getDeclaredField("orderQueue");
            orderQueueField.setAccessible(true);
            ConcurrentLinkedQueue<Pair<String, ArrayList<Object>>> orderQueue = (ConcurrentLinkedQueue<Pair<String, ArrayList<Object>>>) orderQueueField.get(peer);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.PeerManager");
            Field frequencyArrayField = cls.getDeclaredField("frequencyArray");
            frequencyArrayField.setAccessible(true);
            frequencyArrayField.set(peerManager, frequencyArray);
            Field availablePieceListField = cls.getDeclaredField("availablePieceList");
            availablePieceListField.setAccessible(true);
            List<Integer> availablePieceList = (List<Integer>) availablePieceListField.get(peerManager);
            Method method = cls.getDeclaredMethod("updateHaves", Peer.class, int[].class);
            method.setAccessible(true);
            assertEquals(orderQueue.size(), 0);
            assertEquals(0, availablePieceList.size());
            for (int i = 0; i < peerHaves.length; i++) {
                assertEquals(frequencyArray[peerHaves[i]], 0);
                assertFalse(availablePieceList.contains(peerHaves[i]));
            }
            method.invoke(peerManager, peer, haves);
            assertEquals(orderQueue.size(), 0);
            assertEquals(peerHaves.length, availablePieceList.size());
            for (int i = 0; i < haves.length; i++) {
                assertEquals(frequencyArray[peerHaves[i]], 1);
                assertTrue(availablePieceList.contains(peerHaves[i]));
            }
            int i = 0;
            while (i < haves.length) {
                Pair<String, ArrayList<Object>> order = orderQueue.poll();
                if (order == null) {
                    fail("Got a null order");
                    return;
                }
                assertTrue(order.getLeft().equals("have"));
                int idx = (int) order.getRight().get(0);
                assertEquals(idx, haves[i]);
                i += 1;
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testListBehavior() {
        List<Integer> list = new ArrayList<>();
        list.add(Integer.valueOf(2));
        list.add(Integer.valueOf(4));
        list.add(Integer.valueOf(6));
        assertEquals(2, list.get(0));
        list.remove(Integer.valueOf(2));
        assertEquals(4, list.get(0));
    }

    @Test
    void testUpdateRequests() {
        try {
            int pieceNumber = 10;
            long seed = 10000;
            Random randTest = new Random(seed);
            Random randDebug = new Random(seed);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.PeerManager");
            Field randField = cls.getDeclaredField("rand");
            randField.setAccessible(true);
            randField.set(peerManager, randTest);
            Field requestedPiecesField = cls.getDeclaredField("requestedPieces");
            requestedPiecesField.setAccessible(true);
            Map<Integer, Integer> requestedPieces = (Map<Integer, Integer>) requestedPiecesField.get(peerManager);
            Field availablePieceListField = cls.getDeclaredField("availablePieceList");
            availablePieceListField.setAccessible(true);
            List<Integer> availablePieceList = (List<Integer>) availablePieceListField.get(peerManager);
            for (int i = 0; i < pieceNumber; i++) {
                availablePieceList.add(i);
            }
            Method method = cls.getDeclaredMethod("updateRequests", Peer.class);
            method.setAccessible(true);
            method.invoke(peerManager, peer);
            int next = randDebug.nextInt(availablePieceList.size());
            System.out.println(next);
            assertEquals(8, next);
            assertEquals(163840, requestedPieces.get(next));
            assertEquals(10, peer.orderQueue.size());
            for (int i = 0; i < 10; i++) {
                Pair<String, ArrayList<Object>> order = peer.orderQueue.poll();
                if (order == null) {
                    fail("Received no order");
                }
                int reqIndex = (int) order.getRight().get(0);
                int begin = (int) order.getRight().get(1);
                int length = (int) order.getRight().get(2);
                assertEquals(next, reqIndex);
                assertEquals(16384*i, begin);
                assertEquals(16384, length);
            }
            assertEquals(0, peer.orderQueue.size());
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testUpdateRequestsOverflow() {
        try {
            int pieceNumber = 10;
            long seed = 10000;
            Random randTest = new Random(seed);
            Random randDebug = new Random(seed);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.PeerManager");
            Field randField = cls.getDeclaredField("rand");
            randField.setAccessible(true);
            randField.set(peerManager, randTest);
            Field requestedPiecesField = cls.getDeclaredField("requestedPieces");
            requestedPiecesField.setAccessible(true);
            Map<Integer, Integer> requestedPieces = (Map<Integer, Integer>) requestedPiecesField.get(peerManager);
            requestedPieces.put(8, 917504); //Have a begin at 901120 so it overflows with 10 requests
            Field availablePieceListField = cls.getDeclaredField("availablePieceList");
            availablePieceListField.setAccessible(true);
            List<Integer> availablePieceList = (List<Integer>) availablePieceListField.get(peerManager);
            for (int i = 0; i < pieceNumber; i++) {
                availablePieceList.add(i);
            }
            Method method = cls.getDeclaredMethod("updateRequests", Peer.class);
            method.setAccessible(true);
            method.invoke(peerManager, peer);
            int next = randDebug.nextInt(availablePieceList.size());
            System.out.println(next);
            assertEquals(8, next);
            assertEquals(-1, requestedPieces.get(next));
            assertEquals(16384*2, requestedPieces.get(next + 1));
            assertEquals(10, peer.orderQueue.size());
            for (int i = 0; i < 8; i++) {
                Pair<String, ArrayList<Object>> order = peer.orderQueue.poll();
                if (order == null) {
                    fail("Received no order");
                }
                int reqIndex = (int) order.getRight().get(0);
                int begin = (int) order.getRight().get(1);
                int length = (int) order.getRight().get(2);
                assertEquals(next, reqIndex);
                assertEquals(917504 + 16384*i, begin);
                assertEquals(16384, length);
            }
            for (int i = 0; i < 2; i++) {
                Pair<String, ArrayList<Object>> order = peer.orderQueue.poll();
                if (order == null) {
                    fail("Received no order");
                }
                int reqIndex = (int) order.getRight().get(0);
                int begin = (int) order.getRight().get(1);
                int length = (int) order.getRight().get(2);
                assertEquals(next + 1, reqIndex);
                assertEquals(16384*i, begin);
                assertEquals(16384, length);
            }
            assertEquals(0, peer.orderQueue.size());
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testUpdateRequestsOverflowLast() {
        try {
            int pieceNumber = 10;
            long seed = 9574;
            Random randTest = new Random(seed);
            Random randDebug = new Random(seed);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.PeerManager");
            Field frequencyArrayField = cls.getDeclaredField("frequencyArray");
            frequencyArrayField.setAccessible(true);
            frequencyArrayField.set(peerManager, new byte[pieceNumber]);
            Field randField = cls.getDeclaredField("rand");
            randField.setAccessible(true);
            randField.set(peerManager, randTest);
            Field requestedPiecesField = cls.getDeclaredField("requestedPieces");
            requestedPiecesField.setAccessible(true);
            Map<Integer, Integer> requestedPieces = (Map<Integer, Integer>) requestedPiecesField.get(peerManager);
            requestedPieces.put(9, 917504); //Have a begin at 901120 so it overflows with 10 requests
            Field availablePieceListField = cls.getDeclaredField("availablePieceList");
            availablePieceListField.setAccessible(true);
            List<Integer> availablePieceList = (List<Integer>) availablePieceListField.get(peerManager);
            for (int i = 0; i < pieceNumber; i++) {
                availablePieceList.add(i);
            }
            Method method = cls.getDeclaredMethod("updateRequests", Peer.class);
            method.setAccessible(true);
            method.invoke(peerManager, peer);
            int next = randDebug.nextInt(availablePieceList.size());
            System.out.println(next);
            assertEquals(9, next);
            assertEquals(-1, requestedPieces.get(next));
            assertEquals(10, peer.orderQueue.size());
            for (int i = 0; i < 8; i++) {
                Pair<String, ArrayList<Object>> order = peer.orderQueue.poll();
                if (order == null) {
                    fail("Received no order");
                }
                int reqIndex = (int) order.getRight().get(0);
                int begin = (int) order.getRight().get(1);
                int length = (int) order.getRight().get(2);
                assertEquals(next, reqIndex);
                assertEquals(917504 + 16384*i, begin);
                assertEquals(16384, length);
            }
            next = randDebug.nextInt(availablePieceList.size());
            System.out.println(next);
            assertEquals(2, next);
            assertEquals(16384*2, requestedPieces.get(next));
            for (int i = 0; i < 2; i++) {
                Pair<String, ArrayList<Object>> order = peer.orderQueue.poll();
                if (order == null) {
                    fail("Received no order");
                }
                int reqIndex = (int) order.getRight().get(0);
                int begin = (int) order.getRight().get(1);
                int length = (int) order.getRight().get(2);
                assertEquals(next, reqIndex);
                assertEquals(16384*i, begin);
                assertEquals(16384, length);
            }
            assertEquals(0, peer.orderQueue.size());
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void updateRequestsAsynchronousOverflow() {
        try {
            int pieceNumber = 10;
            long seed = 10000;
            Random randTest = new Random(seed);
            Random randDebug = new Random(seed);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.PeerManager");
            Field randField = cls.getDeclaredField("rand");
            randField.setAccessible(true);
            randField.set(peerManager, randTest);
            Field requestedPiecesField = cls.getDeclaredField("requestedPieces");
            requestedPiecesField.setAccessible(true);
            Map<Integer, Integer> requestedPieces = (Map<Integer, Integer>) requestedPiecesField.get(peerManager);
            requestedPieces.put(8, 927504);
            Field availablePieceListField = cls.getDeclaredField("availablePieceList");
            availablePieceListField.setAccessible(true);
            List<Integer> availablePieceList = (List<Integer>) availablePieceListField.get(peerManager);
            for (int i = 0; i < pieceNumber; i++) {
                availablePieceList.add(i);
            }
            Method method = cls.getDeclaredMethod("updateRequests", Peer.class);
            method.setAccessible(true);
            method.invoke(peerManager, peer);
            int next = randDebug.nextInt(availablePieceList.size());
            System.out.println(next);
            assertEquals(8, next);
            assertEquals(-1, requestedPieces.get(next));
            assertEquals(10000 + 16384*2, requestedPieces.get(next + 1));
            assertEquals(10, peer.orderQueue.size());
            for (int i = 0; i < 8; i++) {
                Pair<String, ArrayList<Object>> order = peer.orderQueue.poll();
                if (order == null) {
                    fail("Received no order");
                }
                int reqIndex = (int) order.getRight().get(0);
                int begin = (int) order.getRight().get(1);
                int length = (int) order.getRight().get(2);
                assertEquals(next, reqIndex);
                assertEquals(927504 + 16384*i, begin);
                assertEquals(16384, length);
            }
            for (int i = 0; i < 2; i++) {
                Pair<String, ArrayList<Object>> order = peer.orderQueue.poll();
                if (order == null) {
                    fail("Received no order");
                }
                int reqIndex = (int) order.getRight().get(0);
                int begin = (int) order.getRight().get(1);
                int length = (int) order.getRight().get(2);
                assertEquals(next + 1, reqIndex);
                assertEquals(10000 + 16384*i, begin);
                assertEquals(16384, length);
            }
            assertEquals(0, peer.orderQueue.size());
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void updateRequestsAsynchronousOverflowLast() {
        try {
            int pieceNumber = 10;
            long seed = 9574;
            Random randTest = new Random(seed);
            Random randDebug = new Random(seed);
            Class cls = Class.forName("com.slezevicius.bittorrent_client.PeerManager");
            Field frequencyArrayField = cls.getDeclaredField("frequencyArray");
            frequencyArrayField.setAccessible(true);
            frequencyArrayField.set(peerManager, new byte[pieceNumber]);
            Field randField = cls.getDeclaredField("rand");
            randField.setAccessible(true);
            randField.set(peerManager, randTest);
            Field requestedPiecesField = cls.getDeclaredField("requestedPieces");
            requestedPiecesField.setAccessible(true);
            Map<Integer, Integer> requestedPieces = (Map<Integer, Integer>) requestedPiecesField.get(peerManager);
            requestedPieces.put(9, 927504);
            Field availablePieceListField = cls.getDeclaredField("availablePieceList");
            availablePieceListField.setAccessible(true);
            List<Integer> availablePieceList = (List<Integer>) availablePieceListField.get(peerManager);
            for (int i = 0; i < pieceNumber; i++) {
                availablePieceList.add(i);
            }
            Method method = cls.getDeclaredMethod("updateRequests", Peer.class);
            method.setAccessible(true);
            method.invoke(peerManager, peer);
            int next = randDebug.nextInt(availablePieceList.size());
            System.out.println(next);
            assertEquals(9, next);
            assertEquals(-1, requestedPieces.get(next));
            assertEquals(10, peer.orderQueue.size());
            for (int i = 0; i < 8; i++) {
                Pair<String, ArrayList<Object>> order = peer.orderQueue.poll();
                if (order == null) {
                    fail("Received no order");
                }
                int reqIndex = (int) order.getRight().get(0);
                int begin = (int) order.getRight().get(1);
                int length = (int) order.getRight().get(2);
                assertEquals(next, reqIndex);
                assertEquals(927504 + 16384*i, begin);
                if (i == 7) {
                    assertEquals(6384, length);
                } else {
                    assertEquals(16384, length);
                }
            }
            next = randDebug.nextInt(availablePieceList.size());
            System.out.println(next);
            assertEquals(2, next);
            assertEquals(16384*2, requestedPieces.get(next));
            for (int i = 0; i < 2; i++) {
                Pair<String, ArrayList<Object>> order = peer.orderQueue.poll();
                if (order == null) {
                    fail("Received no order");
                }
                int reqIndex = (int) order.getRight().get(0);
                int begin = (int) order.getRight().get(1);
                int length = (int) order.getRight().get(2);
                assertEquals(next, reqIndex);
                assertEquals(16384*i, begin);
                assertEquals(16384, length);
            }
            assertEquals(0, peer.orderQueue.size());
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            e.getCause().printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    void testGetSortedArrayIndices() {

    }

    @Test
    void testUpdateSortedArrayIndices() {
        //Make sure this gets called when a have is received
    }

    public static class TestingPeer extends Peer {
        private ConcurrentLinkedQueue<Pair<String, ArrayList<Object>>> orderQueue = new ConcurrentLinkedQueue<>();
        public ConcurrentLinkedQueue<Integer> haveQueue;
        public int port;
        int requestCount = 0;

        @Override
        public synchronized int getRequestCount() {
            return requestCount;
        }

        @Override
        public synchronized void incRequestCount() {
            requestCount += 1;
        }

        @Override
        public void addOrder(Pair<String, ArrayList<Object>> order) {
            orderQueue.add(order);
        }

        @Override
        public Integer getPeerHaves() {
            return haveQueue.poll();
        }

        @Override
        public Pair<InetAddress, Integer> getNetworkPair() {
            try {
                return new Pair<InetAddress, Integer>(InetAddress.getByName("localhost"), port);
            } catch (Exception e) {
                e.printStackTrace();
                fail(e.getMessage());
                return null;
            }
        }
    }

    class TestingTorrent extends Torrent {
        public byte[] peerId;
        public byte[] infoHash;
        public byte[] pieces;
        public List<Pair<InetAddress, Integer>> newPeers;

        @Override
        public byte[] getPieces() {
            return pieces;
        }

        @Override
        public List<Pair<InetAddress, Integer>> getNewPeers() {
            return newPeers;
        }

        @Override
        public byte[] getInfoHash() {
            return infoHash;
        }

        @Override
        public int getBitfieldLength() {
            return 12;
        }

        @Override
        public long getPieceLength() {
            return 1048576;
        }

        @Override
        public String getPeerId() {
            try {
                return new String(peerId, "US-ASCII");
            } catch (IOException e) {
                System.out.println("Invalid encoding!");
                return null;
            }
        }
    }
}