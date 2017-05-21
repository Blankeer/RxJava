/**
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.internal.operators;

import rx.*;
import rx.Observable.OnSubscribe;
import rx.exceptions.*;
import rx.functions.Func1;
import rx.plugins.RxJavaHooks;

/**
 * Applies a function of your choosing to every item emitted by an {@code Observable}, and emits the results of
 * this transformation as a new {@code Observable}.
 * <p>
 * <img width="640" height="305" src="https://raw.githubusercontent.com/wiki/ReactiveX/RxJava/images/rx-operators/map.png" alt="">
 *
 * @param <T> the input value type
 * @param <R> the return value type
 */
//NOTE-Blanke: 这是 map 操作符的源码
public final class OnSubscribeMap<T, R> implements OnSubscribe<R> {

    final Observable<T> source;

    final Func1<? super T, ? extends R> transformer;

    public OnSubscribeMap(Observable<T> source, Func1<? super T, ? extends R> transformer) {
        this.source = source;
        this.transformer = transformer; //NOTE-Blanke: 转换函数,也就是我们传入的那个匿名类
    }

    @Override
    public void call(final Subscriber<? super R> o) {
        //NOTE-Blanke: 首先 new 一个新的Subscriber,其实是传参 o 的一个包装类,内部将原始数据T 转为 R,再回调 o 的 onNext() 等
        MapSubscriber<T, R> parent = new MapSubscriber<T, R>(o, transformer);
        //NOTE-Blanke: 这里做的是将新的MapSubscriber,添加到旧的 o 中,
        // 为了保证调用 o 的unsubscribe()和isUnsubscribed() 能调用到 parent 的对应方法中
        o.add(parent);
        source.unsafeSubscribe(parent);//NOTE-Blanke:  parent 订阅 source,也就是用MapSubscriber订阅了未 map 之前的 Observable
    }
    //NOTE-Blanke: actual为真实的Subscriber,就是我们使用时,传入的
    static final class MapSubscriber<T, R> extends Subscriber<T> {

        final Subscriber<? super R> actual;

        final Func1<? super T, ? extends R> mapper;

        boolean done;

        public MapSubscriber(Subscriber<? super R> actual, Func1<? super T, ? extends R> mapper) {
            this.actual = actual;
            this.mapper = mapper;
        }

        @Override
        public void onNext(T t) {
            R result;

            try {
                result = mapper.call(t);//NOTE-Blanke: 首先将原始的数据 T 通过 map 转换函数转成 R
            } catch (Throwable ex) {
                Exceptions.throwIfFatal(ex);
                unsubscribe();
                onError(OnErrorThrowable.addValueAsLastCause(ex, t));
                return;
            }

            actual.onNext(result);//NOTE-Blanke: 转换完成后回调真实的Subscriber.onNext()
        }

        @Override
        public void onError(Throwable e) {
            if (done) {
                RxJavaHooks.onError(e);
                return;
            }
            done = true;

            actual.onError(e);
        }


        @Override
        public void onCompleted() {
            if (done) {
                return;
            }
            actual.onCompleted();
        }

        @Override
        public void setProducer(Producer p) {
            actual.setProducer(p);
        }
    }

}

