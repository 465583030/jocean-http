package org.jocean.http.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.jocean.http.rosa.SignalClient;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ToString;
import org.jocean.idiom.UnsafeOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observable.Transformer;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

public class RxNettys {
    private static final Logger LOG =
            LoggerFactory.getLogger(RxNettys.class);
    private RxNettys() {
        throw new IllegalStateException("No instances!");
    }

    public static final Func1<Object, Object> RETAIN_OBJ = 
            new Func1<Object, Object>() {
        @Override
        public Object call(final Object obj) {
            //    retain obj for blocking
            return ReferenceCountUtil.retain(obj);
        }};
        
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <T> Func1<T, T> retainer() {
        return (Func1)RETAIN_OBJ;
    }
        
    public static <M> Func1<M, ChannelFuture> sendMessage(
            final Channel channel) {
        return new Func1<M,ChannelFuture>() {
            @Override
            public ChannelFuture call(final M msg) {
                return channel.writeAndFlush(ReferenceCountUtil.retain(msg));
            }};
    }
    
    public static <V> GenericFutureListener<Future<V>> makeFailure2ErrorListener(final Subscriber<?> subscriber) {
        return new GenericFutureListener<Future<V>>() {
            @Override
            public void operationComplete(final Future<V> f)
                    throws Exception {
                if (!f.isSuccess()) {
                    subscriber.onError(f.cause());
                }
            }
        };
    }
    
    /*
    private final static Func1<Future<Object>, Observable<Object>> EMITERROR_ONFAILURE = 
    new Func1<Future<Object>, Observable<Object>>() {
        @Override
        public Observable<Object> call(final Future<Object> future) {
            return Observable.create(new OnSubscribe<Object> () {
                @Override
                public void call(final Subscriber<Object> subscriber) {
                    subscriber.add(Subscriptions.from(
                        future.addListener(new GenericFutureListener<Future<Object>>() {
                            @Override
                            public void operationComplete(final Future<Object> f)
                                    throws Exception {
                                if (!f.isSuccess()) {
                                    subscriber.onError(f.cause());
                                }
                            }
                        })));
                }});
        }};
        
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <F extends Future<?>,R> Func1<F, Observable<? extends R>> 
        emitErrorOnFailure() {
        return (Func1)EMITERROR_ONFAILURE;
    }
*/
    /* replace by global one instance
    return new Func1<F, Observable<? extends R>>() {
        @Override
        public Observable<? extends R> call(final F future) {
            return Observable.create(new OnSubscribe<R> () {
                @SuppressWarnings("unchecked")
                @Override
                public void call(final Subscriber<? super R> subscriber) {
                    subscriber.add(Subscriptions.from(
                        future.addListener(new GenericFutureListener<F>() {
                            @Override
                            public void operationComplete(final F future)
                                    throws Exception {
                                if (!future.isSuccess()) {
                                    subscriber.onError(future.cause());
                                }
                            }
                        })));
                }});
        }};
        */
    
    private final static Func1<ChannelFuture, Observable<? extends Channel>> EMITNEXTANDCOMPLETED_ONSUCCESS = 
    new Func1<ChannelFuture, Observable<? extends Channel>>() {
        @Override
        public Observable<? extends Channel> call(final ChannelFuture future) {
            return Observable.create(new OnSubscribe<Channel>() {
                @Override
                public void call(final Subscriber<? super Channel> subscriber) {
                    subscriber.add(Subscriptions.from(
                        future.addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(final ChannelFuture f)
                                    throws Exception {
                                if (f.isSuccess()) {
                                    subscriber.onNext(f.channel());
                                    subscriber.onCompleted();
                                }
                            }
                        })));
                }});
        }};
        
    public static Func1<ChannelFuture, Observable<? extends Channel>> 
        emitNextAndCompletedOnSuccess() {
        return  EMITNEXTANDCOMPLETED_ONSUCCESS;
    }
    
    public static Subscription subscriptionFrom(final Channel channel) {
        return Subscriptions.create(new Action0() {
            @Override
            public void call() {
                channel.close();
            }});
    }

    public static Subscription removeHandlersSubscription(final Channel channel, final String[] names) {
        return Subscriptions.create(new Action0() {
            @Override
            public void call() {
                if (channel.eventLoop().inEventLoop()) {
                    doRemove();
                } else {
                    channel.eventLoop().submit(new Runnable() {
                        @Override
                        public void run() {
                            doRemove();
                        }});
                }
            }
            private void doRemove() {
                final ChannelPipeline pipeline = channel.pipeline();
                for (String name : names) {
                    if (pipeline.context(name) != null) {
                        final ChannelHandler handler = pipeline.remove(name);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("channel({}): remove oneoff Handler({}/{}) success.", channel, name, handler);
                        }
                    }
                }
            }});
    }
    
    public static byte[] httpObjectsAsBytes(final Iterator<HttpObject> itr)
            throws IOException {
        final CompositeByteBuf composite = Unpooled.compositeBuffer();
        try {
            while (itr.hasNext()) {
                final HttpObject obj = itr.next();
                if (obj instanceof HttpContent) {
                    composite.addComponent(((HttpContent)obj).content());
                }
            }
            composite.setIndex(0, composite.capacity());
            
            @SuppressWarnings("resource")
            final InputStream is = new ByteBufInputStream(composite);
            final byte[] bytes = new byte[is.available()];
            is.read(bytes);
            return bytes;
        } finally {
            ReferenceCountUtil.release(composite);
        }
    }
    
    public static final Func1<Object, Boolean> NOT_HTTPOBJECT = new Func1<Object, Boolean>() {
        @Override
        public Boolean call(final Object obj) {
            return !(obj instanceof HttpObject);
        }};
        
    private static final Func1<Object,Boolean> _ISHTTPOBJ = new Func1<Object, Boolean>() {
        @Override
        public Boolean call(final Object obj) {
            return obj instanceof HttpObject;
        }};
    private static final Func1<Object,HttpObject> _OBJ2HTTPOBJ = new Func1<Object, HttpObject> (){
        @Override
        public HttpObject call(final Object obj) {
            return (HttpObject)obj;
        }};
        
    private static final Transformer<Object, HttpObject> _OBJS2HTTPOBJS = 
        new Transformer<Object, HttpObject>() {
        @Override
        public Observable<HttpObject> call(final Observable<Object> objs) {
            return objs.filter(_ISHTTPOBJ).map(_OBJ2HTTPOBJ);
        }};
        
    public static Transformer<Object, HttpObject> objects2httpobjs() {
        return _OBJS2HTTPOBJS;
    }
    
    private static final Func1<Object,Boolean> _PROGRESS = new Func1<Object, Boolean>() {
        @Override
        public Boolean call(final Object obj) {
            return !(obj instanceof SignalClient.Progressable);
        }};
            
    public static <T> Transformer<Object, T> filterProgress() {
        return new Transformer<Object, T>() {
            @Override
            public Observable<T> call(final Observable<Object> objs) {
                return objs.filter(_PROGRESS).map(new Func1<Object, T> (){
                    @SuppressWarnings("unchecked")
                    @Override
                    public T call(final Object obj) {
                        return (T)obj;
                    }});
            }};
    }
    
    public static <T> void releaseObjects(final Collection<T> objs) {
        synchronized (objs) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("start to releaseObjects ({}).", UnsafeOp.toAddress(objs));
                }
                for (T obj : objs) {
                    try {
                        if (ReferenceCountUtil.release(obj)) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("({}) released and deallocated success.", obj); 
                            }
                        } else {
                            if (LOG.isDebugEnabled()) {
                                if ( obj instanceof ReferenceCounted) {
                                    LOG.debug("({}) released BUT refcnt == {} > 0.", obj, ((ReferenceCounted)obj).refCnt()); 
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOG.warn("exception when ReferenceCountUtil.release {}, detail: {}",
                                obj, ExceptionUtils.exception2detail(e));
                    }
                }
            } finally {
                objs.clear();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("end of releaseObjects ({}).", UnsafeOp.toAddress(objs));
                }
            }
        }
    }
    
    public static <T, E extends T> Transformer<? super T, ? extends T> retainAtFirst(
            final Collection<E> objs, 
            final Class<E> elementCls) {
        return new Transformer<T, T>() {
            @Override
            public Observable<T> call(final Observable<T> source) {
                return source.doOnNext(new Action1<T>() {
                    @SuppressWarnings("unchecked")
                    @Override
                    public void call(final T input) {
                        if (input != null && elementCls.isAssignableFrom(input.getClass())) {
                            objs.add(ReferenceCountUtil.retain((E)input));
                            if ( LOG.isDebugEnabled()) {
                                if ( input instanceof ReferenceCounted) {
                                    LOG.debug("({}) retaind,so refcnt is {}.", input, ((ReferenceCounted)input).refCnt()); 
                                }
                            }
                        }
                    }});
            }};
    }
    
    public static <E, T> Transformer<? super T, ? extends T> releaseAtLast(final Collection<E> objs) {
        return new Transformer<T, T>() {
            @Override
            public Observable<T> call(final Observable<T> source) {
                return source.finallyDo(new Action0() {
                        @Override
                        public void call() {
                            if (LOG.isDebugEnabled() ) {
                                LOG.debug("finallyDo: releaseObjects for objs:{}", ToString.toMultiline(objs));
                            }
                            RxNettys.releaseObjects(objs);
                        }})
                    .doOnUnsubscribe(new Action0() {
                        @Override
                        public void call() {
                            if (LOG.isDebugEnabled() ) {
                                LOG.debug("doOnUnsubscribe: releaseObjects for objs:{}", ToString.toMultiline(objs));
                            }
                            RxNettys.releaseObjects(objs);
                        }});
            }};
    }
    
    public static Observable<HttpObject> response401Unauthorized(
            final HttpVersion version, final String vlaueOfWWWAuthenticate) {
        final HttpResponse response = new DefaultFullHttpResponse(
                version, HttpResponseStatus.UNAUTHORIZED);
        HttpHeaders.setHeader(response, HttpHeaders.Names.WWW_AUTHENTICATE, vlaueOfWWWAuthenticate);
        HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_LENGTH, 0);
        return Observable.<HttpObject>just(response);
    }
    
    public static Observable<HttpObject> response200OK(
            final HttpVersion version) {
        final HttpResponse response = new DefaultFullHttpResponse(
                version, HttpResponseStatus.OK);
        HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_LENGTH, 0);
        return Observable.<HttpObject>just(response);
    }
    
    public static FullHttpResponse retainAsFullHttpResponse(final List<HttpObject> httpObjs) {
        if (httpObjs.size()>0) {
            if (httpObjs.get(0) instanceof FullHttpResponse) {
                return ((FullHttpResponse)httpObjs.get(0)).retain();
            }
            
            final HttpResponse resp = (HttpResponse)httpObjs.get(0);
            final ByteBuf[] bufs = new ByteBuf[httpObjs.size()-1];
            for (int idx = 1; idx<httpObjs.size(); idx++) {
                bufs[idx-1] = ((HttpContent)httpObjs.get(idx)).content().retain();
            }
            return new DefaultFullHttpResponse(
                    resp.getProtocolVersion(), 
                    resp.getStatus(),
                    Unpooled.wrappedBuffer(bufs));
        } else {
            return null;
        }
    }
}
