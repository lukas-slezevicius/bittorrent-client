import socket
import select
import sys
import logging

def main(debuggerPort, peerPort, recv_block_size=4096):
    try:
        debugger = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        debugger.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        logging.info("Connecting to debugger")
        debugger.connect(("localhost", debuggerPort))
        debugger.setblocking(0)
        collector = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        collector.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        collector.setblocking(0)
        collector.bind(("localhost", peerPort))
        collector.listen(1)
        potential_readers = [debugger, collector]
        potential_writers = []
        potential_errors = [debugger, collector]
        collector_sockets = []
        data = None
        received_from_debugger = False
        received_from_peer = False
        while True:
            readable, writeable, exception_available = select.select(
                    potential_readers, potential_writers, potential_errors)
            for s in readable:
                if s is debugger:
                    data = debugger.recv(recv_block_size)
                    received_from_debugger = True
                    logging.info(f"Received from debugger: {data}")
                elif s is collector:
                    logging.info("Adding a new peer")
                    conn, addr = collector.accept()
                    conn.setblocking(0)
                    potential_readers.append(conn)
                    potential_writers.append(conn)
                    potential_errors.append(conn)
                    collector_sockets.append(conn)
                else:
                    data = s.recv(recv_block_size)
                    received_from_peer = True
                    potential_writers.append(debugger)
                    logging.info(f"Received from peer: {data}")
            for s in writeable:
                if s in collector_sockets and data is not None and received_from_debugger:
                    logging.info(f"Sending {data} to peer and closing it")
                    s.sendall(data)
                    collector_sockets.remove(s)
                    potential_readers.remove(s)
                    potential_writers.remove(s)
                    potential_errors.remove(s)
                    s.close()
                    data = None
                    received_from_debugger = False
                if s is debugger and data is not None and received_from_peer:
                    logging.info(f"Sending {data} to debugger")
                    debugger.sendall(data)
                    data = None
                    received_from_peer = False
                    potential_writers.remove(debugger)
                    peer = collector_sockets[0]
                    collector_sockets.remove(peer)
                    potential_readers.remove(peer)
                    potential_writers.remove(peer)
                    potential_errors.remove(peer)
                    peer.close()
            for s in exception_available:
                logging.warning("Exception available in socket")
    except KeyboardInterrupt:
        logging.info("Closing the mock peer")
        debugger.close()
        collector.close()
    except Exception as e:
        logging.exception("Exception")

if __name__ == "__main__":
    logging.basicConfig(filename="MockPeer.log",
            filemode='a',
            format='%(asctime)s,%(msecs)d %(name)s %(levelname)s %(message)s',
            datefmt='%H:%M:%S',
            level=logging.DEBUG)
    logging.info("Starting echo server.")
    main(int(sys.argv[1]), int(sys.argv[2]))
