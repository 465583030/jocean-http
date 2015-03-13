/**
 * 
 */
package org.jocean.http.client.impl;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.client.HttpClient;
import org.jocean.idiom.Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;

/**
 * @author isdom
 *
 */
public class DefaultHttpClient implements HttpClient {
	
    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
        }
    }
    
	private static final Logger LOG =
			LoggerFactory.getLogger(DefaultHttpClient.class);
	
	private final Callable<Channel> NEW_CHANNEL = new Callable<Channel>() {
		@Override
		public Channel call() throws Exception {
			final Channel ch = _bootstrap.register().channel();
			if ( LOG.isDebugEnabled() ) {
				LOG.debug("create new channel: {}", ch);
			}
			return	ch;
		}};

	/* (non-Javadoc)
	 * @see org.jocean.http.client.HttpClient#sendRequest(java.net.URI, rx.Observable)
	 * eg: new SocketAddress(this._uri.getHost(), this._uri.getPort()))
	 */
	@Override
	public Observable<HttpObject> sendRequest(
			final SocketAddress remoteAddress,
			final Observable<HttpObject> request,
			final Feature... features) {
		final int featuresAsInt = this._defaultFeaturesAsInt | Features.featuresAsInt(features);
		return Observable.create(
				new OnSubscribeResponse(
						featuresAsInt, 
						Features.isEnabled(featuresAsInt, Feature.EnableSSL) ? this._sslCtx : null,
						NEW_CHANNEL,
						remoteAddress, 
						request));
	}
	
	public DefaultHttpClient() throws Exception {
		this(1);
	}
	
	public DefaultHttpClient(final int processThreadNumber) throws Exception {
		this(new NioEventLoopGroup(processThreadNumber), NioSocketChannel.class);
	}
	
    private static final class BootstrapChannelFactory<T extends Channel> implements ChannelFactory<T> {
        private final Class<? extends T> clazz;

        BootstrapChannelFactory(Class<? extends T> clazz) {
            this.clazz = clazz;
        }

        @Override
        public T newChannel() {
            try {
                return clazz.newInstance();
            } catch (Throwable t) {
                throw new ChannelException("Unable to create Channel from class " + clazz, t);
            }
        }

        @Override
        public String toString() {
            return StringUtil.simpleClassName(clazz) + ".class";
        }
    }
    
	public DefaultHttpClient(
			final EventLoopGroup eventLoopGroup,
			final Class<? extends Channel> channelType,
			final Feature... defaultFeatures) throws Exception { 
	    this(eventLoopGroup, 
            new BootstrapChannelFactory<Channel>(channelType), 
            defaultFeatures);
    }
	
    public DefaultHttpClient(
            final EventLoopGroup eventLoopGroup,
            final ChannelFactory<? extends Channel> channelFactory,
            final Feature... defaultFeatures) throws Exception { 
        this._defaultFeaturesAsInt = Features.featuresAsInt(defaultFeatures);
        // Configure the client.
        this._bootstrap = new Bootstrap()
            .group(eventLoopGroup)
            .channelFactory(channelFactory)
            .handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(final Channel channel) throws Exception {
                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) throws Exception {
                            ctx.fireChannelActive();
                            _activeChannelCount.incrementAndGet();
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                            ctx.fireChannelInactive();
                            _activeChannelCount.decrementAndGet();
                        }
                    });
                }})
            ;
        this._sslCtx = SslContext.newClientContext(InsecureTrustManagerFactory.INSTANCE);
    }
    
	/* (non-Javadoc)
	 * @see java.io.Closeable#close()
	 */
	@Override
	public void close() throws IOException {
        // Shut down executor threads to exit.
        this._bootstrap.group().shutdownGracefully(100, 1000, TimeUnit.MILLISECONDS).syncUninterruptibly();
	}
	
	public int getActiveChannelCount() {
	    return this._activeChannelCount.get();
	}
	
	private final AtomicInteger _activeChannelCount = new AtomicInteger(0);
	private final Bootstrap _bootstrap;
    private final SslContext _sslCtx;
    private final int _defaultFeaturesAsInt;
}
