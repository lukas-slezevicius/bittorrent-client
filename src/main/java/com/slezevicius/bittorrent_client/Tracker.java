package com.slezevicius.bittorrent_client;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.zip.DataFormatException;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

class Tracker {
    private Metainfo metainfo;
    private Torrent torrent;
    private long interval;
    private String trackerId;
    private long complete;
    private long incomplete;
    private Set<Pair<InetAddress, Integer>> peers;


    Tracker(Metainfo metainfo, Torrent torrent) throws URISyntaxException, DataFormatException {
        this.metainfo = metainfo;
        this.torrent = torrent;
        send("started");
    }

    private void send(String event) throws URISyntaxException, DataFormatException {
        String trackerURI = buildTrackerURL(event);
        byte[] responseContent = sendRequest(trackerURI);
        Bencoding b = new Bencoding(responseContent);
        try {
            Object responseObject = b.decode();
            updateFields(responseObject);
        } catch (DataFormatException e) {
            e.printStackTrace();
        }
    }

    private void updateFields(Object responseObject) throws DataFormatException {
        //Come up with better error handling for the errors in this function
        if (responseObject instanceof LinkedHashMap) {
            LinkedHashMap<String, Object> responseDict = (LinkedHashMap<String, Object>) responseObject;
            if (responseDict.containsKey("failure reason")) {
                if (responseDict.get("failure reason") instanceof byte[]) {
                    throw new RuntimeException(new String((byte[]) responseDict.get("failure reason")));
                } else {
                    throw new RuntimeException("failure reason type is not byte string");
                }
            }
            if (responseDict.containsKey("warning message")) {
                if (responseDict.get("warning message") instanceof byte[]) {
                    System.out.println(new String((byte[]) responseDict.get("warning message")));
                } else {
                    throw new RuntimeException("Warning message type is not byte string");
                }
            }
            if (responseDict.containsKey("interval")) {
                if (responseDict.get("interval") instanceof Long) {
                    interval = (long) responseDict.get("interval");
                } else {
                    throw new RuntimeException("interval value is not of type int");
                }
            } else {
                throw new RuntimeException("interval key not in the dictionary");
            }
            if (responseDict.containsKey("complete")) {
                if (responseDict.get("complete") instanceof Long) {
                    complete = (long) responseDict.get("complete");
                } else {
                    throw new RuntimeException("complete value is not of type int");
                }
            } else {
                throw new RuntimeException("complete key not in the dictionary");
            }
            if (responseDict.containsKey("incomplete")) {
                if (responseDict.get("incomplete") instanceof Long) {
                    incomplete = (long) responseDict.get("incomplete");
                } else {
                    throw new RuntimeException("incomplete value is not of type int");
                }
            } else {
                throw new RuntimeException("incomplete key not in the dictionary");
            }
            if (responseDict.containsKey("peers")) {
                if (responseDict.get("peers") instanceof ArrayList) {
                    //List model of peers
                } else if (responseDict.get("peers") instanceof byte[]) {
                    //Compact model of peers
                    updatePeers((byte[]) responseDict.get("peers"));
                } else {
                    throw new RuntimeException("Peers is of an invalid type");
                }
            } else {
                throw new RuntimeException("peers key not in the dictionary");
            }
        } else {
            throw new DataFormatException("The response from the tracker must be a dictionary.");
        }
    }

    private void updatePeers(byte[] peerBytes) {
        if (peerBytes.length % 6 != 0) {
            throw new RuntimeException("Invalid length of peer byte array");
        }
        peers = new HashSet<Pair<InetAddress, Integer>>(50);
        int i = 0;
        while (i < peerBytes.length) {
            InetAddress ip;
            try {
                ip = InetAddress.getByAddress(Arrays.copyOfRange(peerBytes, i, i + 4));
            } catch (UnknownHostException e) {
                e.printStackTrace();
                throw new RuntimeException();
            }
            int port = (peerBytes[i + 4] & 0xFF)*256 + (peerBytes[i + 4 +1] & 0xFF);
            peers.add(new Pair<InetAddress, Integer>(ip, port));
            i += 6;
        }
    }

    private String buildTrackerURL(String event) {
        StringBuilder url = new StringBuilder(metainfo.getAnnounce());
        url.append("?info_hash=");
        url.append(percentEncode(metainfo.getInfoHash()));
        url.append("&peer_id=");
        url.append(torrent.getPeerId());
        url.append("&port=");
        url.append(String.valueOf(torrent.getPort()));
        url.append("&uploaded=");
        url.append(String.valueOf(torrent.getUploaded()));
        url.append("&downloaded=");
        url.append(String.valueOf(torrent.getDownloaded()));
        url.append("&left=");
        url.append(String.valueOf(metainfo.getLength() - torrent.getDownloaded()));
        url.append("&compact=1");
        url.append("&event=");
        url.append(event);
        if (trackerId != null) {
            url.append("&trackerid=");
            url.append(trackerId);
        }
        return new String(url);
    }

    private static String percentEncode(byte[] in) {
        StringBuilder out = new StringBuilder(in.length * 2);
        for (int i = 0; i < in.length; i++) {
            if (isLetterOrDigit(in[i]) || isSpecialChar(in[i])) {
                out.append((char) in[i]);
            } else {
                out.append('%');
                out.append(Metainfo.bytesToHex(Arrays.copyOfRange(in, i, i + 1)));
            }
        }
        return new String(out);
    }

    private static boolean isLetterOrDigit(byte c) {
        if ((c >= 97 && c <= 122) || (c >= 65 && c <= 90) || (c >= 48 && c <= 57)) {
            return true;
        }
        return false;
    }

    private static boolean isSpecialChar(byte c) {
        if (c == '_' || c == '-' || c == '.' || c == '~') {
            return true;
        }
        return false;
    }

    private static byte[] sendRequest(String url) {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpGet httpGet = new HttpGet(url);
        try {
            CloseableHttpResponse response = httpclient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            InputStream stream = entity.getContent();
            byte[] content = stream.readAllBytes();
            stream.close();
            EntityUtils.consume(entity);
            return content;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public long getInterval() {
        return interval;
    }

    public long getComplete() {
        return complete;
    }

    public long getIncomplete() {
        return incomplete;
    }

    public Set<Pair<InetAddress, Integer>> getPeers() {
        return peers;
    }
}

class Pair<L, R> {
    private final L left;
    private final R right;

    public Pair(L left, R right) {
        assert left != null;
        assert right != null;

        this.left = left;
        this.right = right;
    }

    public L getLeft() {
        return left;
    }

    public R getRight() {
        return right;
    }

    @Override
    public int hashCode() {
        return left.hashCode() ^ right.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Pair)) {
            return false;
        }
        Pair pairObj = (Pair) obj;
        return this.left.equals(pairObj.getLeft()) && this.right.equals(pairObj.getRight());
    }

    @Override
    public String toString() {
        return String.format("<%s, %s>", this.left.toString(), this.right.toString());
    }
}