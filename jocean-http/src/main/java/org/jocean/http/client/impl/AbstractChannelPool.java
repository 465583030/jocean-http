package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.Future;

import java.net.SocketAddress;

import org.jocean.http.client.OutboundFeature;
import org.jocean.http.client.OutboundFeature.Applicable;
import org.jocean.http.client.OutboundFeature.FeaturesAware;
import org.jocean.http.util.ChannelSubscriberAware;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.rx.OneshotSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Func1;

public abstract class AbstractChannelPool implements ChannelPool {
    
    @SuppressWarnings("unused")
    private static final Logger LOG =
            LoggerFactory.getLogger(AbstractChannelPool.class);

    protected AbstractChannelPool(final ChannelCreator channelCreator) {
        this._channelCreator = channelCreator;
    }
    
    @Override
    public Observable<? extends Channel> retainChannel(
            final SocketAddress address, 
            final Applicable[] features) {
        return Observable.create(new OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    try {
                        final Channel channel = reuseChannel(address);
                        if (null!=channel) {
                            subscriber.add(recycleChannelSubscription(channel));
                            final Runnable runnable = buildOnNextRunnable(subscriber, channel, features);
                            if (channel.eventLoop().inEventLoop()) {
                                runnable.run();
                            } else {
                                RxNettys.<Future<?>,Channel>emitErrorOnFailure()
                                    .call(channel.eventLoop().submit(runnable))
                                    .subscribe(subscriber);
                            }
                        } else {
                            onNextNewChannel(subscriber, address, features);
                        }
                    } catch (Throwable e) {
                        subscriber.onError(e);
                    }
                }
            }});
    }
    
    private Runnable buildOnNextRunnable(
            final Subscriber<? super Channel> subscriber,
            final Channel channel, 
            final Applicable[] features) {
        return new Runnable() {
            @Override
            public void run() {
                prepareChannelSubscriberAware(subscriber, features);
                subscriber.add(OutboundFeature.applyOneoffFeatures(channel, features));
                subscriber.onNext(channel);
                subscriber.onCompleted();
            }};
    }

    private void onNextNewChannel(
            final Subscriber<? super Channel> subscriber,
            final SocketAddress address,
            final Applicable[] features) {
        
        prepareChannelSubscriberAware(subscriber, features);
        prepareFeaturesAware(features);

        final ChannelFuture future = this._channelCreator.newChannel();
        subscriber.add(recycleChannelSubscription(future.channel()));
        RxNettys.<ChannelFuture,Channel>emitErrorOnFailure()
            .call(future)
            .subscribe(subscriber);
        RxNettys.emitNextAndCompletedOnSuccess()
            .call(future)
            .flatMap(new Func1<Channel, Observable<? extends Channel>> () {
                @Override
                public Observable<? extends Channel> call(final Channel channel) {
                    ChannelPool.Util.attachChannelPool(channel, AbstractChannelPool.this);
                    OutboundFeature.applyNononeoffFeatures(channel, features);
                    subscriber.add(
                        OutboundFeature.applyOneoffFeatures(channel, features));
                    return RxNettys.<ChannelFuture, Channel>emitErrorOnFailure()
                        .call(channel.connect(address));
                }})
            .subscribe(subscriber);
    }

    private void prepareFeaturesAware(final Applicable[] features) {
        final FeaturesAware featuresAware = 
                InterfaceUtils.compositeIncludeType(features, FeaturesAware.class);
        if (null!=featuresAware) {
            featuresAware.setApplyFeatures(features);
        }
    }

    private void prepareChannelSubscriberAware(
            final Subscriber<? super Channel> subscriber,
            final Applicable[] features) {
        final ChannelSubscriberAware channelSubscriberAware = 
                InterfaceUtils.compositeIncludeType(features, ChannelSubscriberAware.class);
        if (null!=channelSubscriberAware) {
            channelSubscriberAware.setChannelSubscriber(subscriber);
        }
    }
    
    private Subscription recycleChannelSubscription(final Channel channel) {
        return new OneshotSubscription() {
            @Override
            protected void doUnsubscribe() {
                if (channel.eventLoop().inEventLoop()) {
                    recycleChannel(channel);
                } else {
                    channel.eventLoop().submit(new Runnable() {
                        @Override
                        public void run() {
                            recycleChannel(channel);
                        }});
                }
            }};
    }
    
    protected abstract Channel reuseChannel(final SocketAddress address);
    
    private final ChannelCreator _channelCreator;
}
