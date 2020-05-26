package com.slezevicius.bittorrent_client;

public class Triplet<L, M, R> {
    private final L left;
    private final M middle;
    private final R right;

    public Triplet(L left, M middle, R right) {
        this.left = left;
        this.middle = middle;
        this.right = right;
    }

    public L getLeft() {
        return left;
    }

    public M getMiddle() {
        return middle;
    }

    public R getRight() {
        return right;
    }

    @Override
    public int hashCode() {
        return left.hashCode() ^ middle.hashCode() ^ right.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Triplet)) {
            return false;
        }
        Triplet tripletObj = (Triplet) obj;
        return this.left.equals(tripletObj.getLeft())&& this.middle.equals(tripletObj.getMiddle()) && this.right.equals(tripletObj.getRight());
    }

    @Override
    public String toString() {
        return String.format("<%s, %s, %s>", this.left.toString(), this.middle.toString(), this.right.toString());
    }
}