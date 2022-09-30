package shadow;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

public class NetStream {

    SocketChannel channel;
    Selector selector;

    public void connect(String address, int port) throws Exception {
        channel = SocketChannel.open();
        channel.configureBlocking(false);
        channel.connect(new InetSocketAddress(address, port));
        selector = Selector.open();
        channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE | SelectionKey.OP_CONNECT);
    }

    public void process() throws IOException {
        while (selector.select() > 0) {
            Set<SelectionKey> keys = selector.selectedKeys();
            Iterator<SelectionKey> it = keys.iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                SocketChannel channel = (SocketChannel) key.channel();
                if (key.isConnectable()) {
                    try {
                        while (channel.isConnectionPending()) {
                            channel.finishConnect();
                        }
                    } catch (IOException  e) {
                        key.cancel();
                        e.printStackTrace();
                        return;
                    }

                } else {
                    if (key.isReadable()) {

                    }

                    if (key.isWritable()) {

                    }
                }
            }
        }
    }

}
