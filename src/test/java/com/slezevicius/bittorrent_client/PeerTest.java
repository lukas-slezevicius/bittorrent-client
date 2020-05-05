package com.slezevicius.bittorrent_client;

public class PeerTest {
    public class TestingPeerManager extends PeerManager {
        private String peerId;
        private byte[] infoHash;
        private byte[] bitfield;
        private int bitfieldLength;

        public void setPeerId(String peerId) {
            this.peerId = peerId;
        }

        public void setInfoHash(byte[] infoHash) {
            this.infoHash = infoHash;
        }

        public void setBitfield(byte[] bitfield) {
            this.bitfield = bitfield;
            this.bitfieldLength = bitfield.length;
        }

        @Override
        public String getPeerId() {
            return peerId;
        }

        @Override
        public byte[] getInfoHash() {
            return infoHash;
        }

        @Override
        public byte[] getBitfield() {
            return bitfield;
        }

        @Override
        public int getBitfieldLength() {
            return bitfieldLength;
        }

        @Override
        public void shutdown() {

        }
    }
}