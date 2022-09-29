package com.dji.ux.beta.sample.util;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.sdkmanager.DJISDKManager;
import dji.thirdparty.afinal.core.AsyncTask;
import io.agora.base.JavaI420Buffer;
import io.agora.base.VideoFrame;
import io.agora.rtc2.ChannelMediaOptions;
import io.agora.rtc2.Constants;
import io.agora.rtc2.IRtcEngineEventHandler;
import io.agora.rtc2.RtcEngine;
import io.agora.rtc2.RtcEngineConfig;
import io.agora.rtc2.UserInfo;
import io.agora.rtc2.video.EncodedVideoFrameInfo;

/**
 * desc:    声网工具
 * author:  神雨田
 * date:    2022-09-27 11:22
 */
public class AgoraUtil {
    private final String TAG = "AgoraUtil test";
    private RtcEngine engine;
    private String token = "";
    private String appId = "";
    private String channel = "test";
    private EncodedVideoFrameInfo encodedVideoFrameInfo;
    private boolean isSendData = false;
    private ByteBuffer buffer;
    private JavaI420Buffer i420Buffer;
    private VideoFrame videoFrame;
    private Listener listener;

    private DJICodecManager codecManager;

    private static AgoraUtil agoraUtil;

    private AgoraUtil() {

    }

    public static AgoraUtil getInstance() {
        if (agoraUtil == null) agoraUtil = new AgoraUtil();
        return agoraUtil;
    }

    /**
     * 有图传才能进行 初始化
     */
    public boolean initEngine(Context context, String appId, String token, String channel, Listener listener) {
        try {
//            if (TextUtils.isEmpty(appId)) {
//                appId = context.getString(R.string.agora_app_id);
//            }
//            if (TextUtils.isEmpty(token)) {
//                token = context.getString(R.string.agora_access_token);
//            }
            this.appId = appId;
            this.channel = channel;
            this.token = token;
            RtcEngineConfig config = new RtcEngineConfig();
            config.mContext = context.getApplicationContext();
            config.mAppId = appId;
            config.mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING;
            config.mEventHandler = iRtcEngineEventHandler;
            engine = RtcEngine.create(config);
        }
        catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "initEngine: " + e.getLocalizedMessage());
            if (listener != null) {
                listener.initError("initEngine: " + e.getLocalizedMessage());
            }
            return false;
        }
        // 初始化 图传参数
        encodedVideoFrameInfo = new EncodedVideoFrameInfo();
        encodedVideoFrameInfo.framesPerSecond = 30;
        // 视频帧的宽度 (px)
        encodedVideoFrameInfo.width= 1280;
        encodedVideoFrameInfo.height= 720;
        // 1：标准 VP8 2：标准 H.264
        encodedVideoFrameInfo.codecType = 2;
        return true;
    }

    /**
     * 有图传，这里才能执行推流
     */
    public boolean start() {
        engine.setExternalVideoSource(true, false, Constants.ExternalVideoSourceType.ENCODED_VIDEO_FRAME);
        ChannelMediaOptions mediaOptions = new ChannelMediaOptions();
        mediaOptions.autoSubscribeAudio = false;
        mediaOptions.autoSubscribeVideo = false;
        mediaOptions.publishEncodedVideoTrack = true;
        mediaOptions.publishCameraTrack = false;
        mediaOptions.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER;
        mediaOptions.channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING;
        int join = engine.joinChannel(token, channel, 0, mediaOptions);
        if (join == 0) {
            engine.enableVideo();
            Log.d(TAG,"加入频道成功");
            isSendData = true;
        } else {
            Log.d(TAG,"加入频道失败 " + join);
            if (listener != null) {
                listener.startError("加入频道失败: " + join);
            }
            return false;
        }
        VideoFeeder.VideoFeed videoFeed = VideoFeeder.getInstance().provideTranscodedVideoFeed();
        videoFeed.addVideoDataListener(videoDataListenerStandard);
        /**
         * 项目解码后的视频流，没有回调，取消下面的注释
         */
//        VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(videoDataListenerOriginal);
        return true;
    }

    public void exit() {
        engine.leaveChannel();
        engine.disableVideo();
        isSendData = false;
        if (DJISDKManager.getInstance().getProduct().getCamera() != null) {
            if (VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
                VideoFeeder.getInstance().getPrimaryVideoFeed().removeVideoDataListener(videoDataListenerOriginal);
            }
            if (VideoFeeder.getInstance().provideTranscodedVideoFeed() != null) {
                VideoFeeder.getInstance().provideTranscodedVideoFeed().removeVideoDataListener(videoDataListenerStandard);
            }
        }
    }

    public void stop() {
        RtcEngine.destroy();
    }

    /**
     * 输入 h264数据 Nal
     * @param bytes
     */
    public void pushData(byte[] bytes) {
        if (isSendData) {
            AsyncTask.execute(() -> {
                // bytes[4] == nal header 信息 低五位表示nal类型
                // 0: 黑帧 3: 关键帧 4: Delta 帧5: B 帧 6: 丢弃帧   0x27 7序列参数集 dji中每一个带sps的都是 I帧
                encodedVideoFrameInfo.frameType=bytes[4]== 0x27 ? 3 : 4;
//                dumpFile(bytes);
                //初始化一个和byte长度一样的buffer
                buffer = ByteBuffer.allocateDirect(bytes.length);
                // 数组放到buffer中
                buffer.put(bytes);
                engine.pushExternalEncodedVideoFrame(buffer, encodedVideoFrameInfo);
            });
        }
    }

    String fileName;
    private void dumpFile(byte[] bytes) {
        try {
            fileName = "/sdcard/Download/zixi/djiData_" + System.currentTimeMillis() + ".h264";
            FileOutputStream outStream = new FileOutputStream(fileName);
            outStream.write(bytes);
            outStream.close();
            Log.d(TAG, "写入文件 " + fileName);
        } catch (IOException io) {
            throw new RuntimeException("将数据写入文件失败" + fileName, io);
        }
    }

    public boolean isSendData() {
        return isSendData;
    }

    /**
     * 输入yuv 图像数据，适配mini等机型，无法输出 h264编码视频的，只能取yuv数据来进行上传。
     * @param mediaFormat 格式 MediaFormat
     * @param yuvFrame  数据
     * @param dataSize 数据大小
     * @param width 宽
     * @param height 高
     */
    public void pushYUVData(MediaFormat mediaFormat, ByteBuffer yuvFrame, int dataSize, int width, int height) {
        //在这个演示中，我们通过将YUV数据保存到JPG文件中来测试它。DJILog日志。d（标签，“onYuvDataReceived”+数据大小）；
        if (yuvFrame != null) {
            final byte[] bytes = new byte[dataSize];
            yuvFrame.get(bytes);
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    // 这里有两个示例，它可能有其他颜色格式。
                    int colorFormat = mediaFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT);
//                    Log.d(TAG, "colorFormat " + colorFormat);
                    switch (colorFormat) {
                        // NV12
                        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                            if (isSendData) {
                                // 绘制界面 todo
                                if (bytes.length < width * height) {
                                    Log.d(TAG, "yuvFrame 大小太小 " + bytes.length);
                                    return;
                                }
                                //NV12 转换成 i420 -> 直接取出数据就行了
                                byte[] u = new byte[width * height / 4];
                                byte[] v = new byte[width * height / 4];
                                int length = width * height;
                                for (int i = 0; i < u.length; i++) {
                                    u[i] = bytes[length + 2 * i];
                                    v[i] = bytes[length + 2 * i + 1];
                                }
                                // 试试这个数据怎么样？
                                if (engine != null) {
                                    i420Buffer = JavaI420Buffer.allocate(width, height);
                                    i420Buffer.getDataY().put(bytes, 0, length);
                                    i420Buffer.getDataU().put(u);
                                    i420Buffer.getDataV().put(v);
                                    videoFrame = new VideoFrame(i420Buffer, 0, System.nanoTime());
                                    boolean b = engine.pushExternalVideoFrame(videoFrame);
                                    Log.d(TAG, "推流 i420Buffer = " + i420Buffer + "  推送成功 = " + b);
                                }
                            }
                            break;
                        case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
                            //YUV420P

                            break;
                        default:
                            break;
                    }

                }
            });
        }
    }

    // 原始视频
    private VideoFeeder.VideoDataListener videoDataListenerOriginal = (videoBuffer, size) -> {

        if (codecManager == null) {
//            codecManager = new DJICodecManager(, , , );
        }
//        codecManager.sendDataToDecoder(videoBuffer, size);
    };

    // 编码后的视频
    private VideoFeeder.VideoDataListener videoDataListenerStandard = (videoBuffer, size) -> {
        /**
         * 当这个回调没有反应时，取消 129 行注释, 然后初始化259行，给图传的纹理
         */
        pushData(videoBuffer);
    };

    private final IRtcEngineEventHandler iRtcEngineEventHandler = new IRtcEngineEventHandler() {

        @Override
        public void onError(int err) {
            super.onError(err);
            /**
             * 0：没有错误。
             * 1：一般性的错误（没有明确归类的错误原因）。
             * 2：API 调用了无效的参数。例如指定的频道名含有非法字符。
             * 3：SDK 初始化失败。Agora 建议尝试以下处理方法：
             * 检查音频设备状态
             * 检查程序集完整性
             * 尝试重新初始化 SDK
             * 4：SDK 当前状态不支持此操作。
             * 5：调用被拒绝。
             * 6：传入的缓冲区大小不足以存放返回的数据。
             * 7：SDK 尚未初始化，就调用其 API。请确认在调用 API 之前已创建 RtcEngine 对象并完成初始化。
             * 9：没有操作权限。请检查用户是否授予 app 音视频设备使用权限。
             * 10：API 调用超时。有些 API 调用需要 SDK 返回结果，如果 SDK 处理时间过长，超过 10 秒没有返回，会出现此错误。
             * 11：请求被取消。仅供 SDK 内部使用，不通过 API 或者回调事件返回给 App。
             * 12：调用频率太高。仅供 SDK 内部使用，不通过 API 或者回调事件返回给 App。
             * 13：SDK 内部绑定到网络 Socket 失败。仅供 SDK 内部使用，不通过 API 或者回调事件返回给 App。
             * 14：网络不可用。仅供 SDK 内部使用，不通过 API 或者回调事件返回给 App。
             * 15：没有网络缓冲区可用。仅供 SDK 内部使用，不通过 API 或者回调事件返回给 App。
             * 17：加入频道被拒绝。一般有以下原因：
             * 用户已进入频道，再次调用加入频道的 API，例如 joinChannel，会返回此错误。停止调用该方法即可。
             * 用户在做 Echo 测试时尝试加入频道。等待 Echo test 结束后再加入频道即可。
             * 使用已过期的 Token 调用 joinChannel 时也会返回此错误。请使用有效的 Token 重新加入频道。
             * 18：离开频道失败。一般有以下原因：
             * 用户已离开频道，再次调用退出频道的 API，例如 leaveChannel，会返回此错误。停止调用该方法即可。
             * 用户尚未加入频道，就调用退出频道的 API。这种情况下无需额外操作。
             * 19：资源已被占用，不能重复使用。
             * 101：不是有效的 APP ID。请更换有效的 APP ID 重新加入频道。
             * 102：不是有效的频道名。请更换有效的频道名重新加入频道。
             * 103: 没有服务器资源，请尝试设置其他区域代码。
             * 109：当前使用的 Token 过期，不再有效。
             * 110：生成的 Token 无效。
             * 111：网络连接中断。仅适用于 Agora Web SDK。
             * 112：网络连接丢失。仅适用于 Agora Web SDK。
             * 113：用户不在频道内。
             * 114：在调用 sendStreamMessage 时，当发送的数据长度大于 1024 个字节时，会发生该错误。
             * 115：在调用 sendStreamMessage 时，当发送的数据码率超过限制（6KB/s）时，会发生该错误。
             * 116：在调用 createDataStream 时，如果创建的数据通道过多（超过 5 个通道），会发生该错误。
             * 120：解密失败，可能是用户加入频道用了不同的密码。请检查加入频道时的设置，或尝试重新加入频道。
             * 123：此用户被服务器禁止。服务端踢人场景时会报这个错。
             * 125：水印文件路径错误。
             * 134：无效的 User account。
             * 1001：加载媒体引擎失败。
             * 1002：启动媒体引擎开始通话失败。请尝试重新进入频道。
             * 1003：启动摄像头失败，请检查摄像头是否被其他应用占用，或者尝试重新进入频道。
             * 1004：启动视频渲染模块失败。
             * 1005：音频设备模块：音频设备出现错误（未明确指明为何种错误）。请检查音频设备是否被其他应用占用，或者尝试重新进入频道。
             * 1006：音频设备模块：使用 java 资源出现错误。
             * 1007：音频设备模块：设置的采样频率出现错误。
             * 1008：音频设备模块：初始化播放设备出现错误。请检查播放设备是否被其他应用占用，或者尝试重新进入频道。
             * 1009：音频设备模块：启动播放设备出现错误。请检查播放设备是否正常。
             * 1010：音频设备模块：停止播放设备出现错误。
             * 1011：音频设备模块：初始化采集设备时出现错误。请检查采集设备是否正常，或者尝试重新进入频道。
             * 1012：音频设备模块：启动采集设备出现错误。请检查采集设备是否正常。
             * 1013：音频设备模块：停止采集设备出现错误。
             * 1015：音频设备模块：运行时播放出现错误。请检查采集设备是否正常，或者尝试重新进入频道。
             * 1017：音频设备模块：运行时采集错误。请检查采集设备是否正常，或者尝试重新进入频道。
             * 1018：音频设备模块：采集失败。
             * 1022：音频设备模块：初始化 Loopback 设备错误。
             * 1023：音频设备模块：启动 Loopback 设备错误。
             * 1030：音频路由：连接蓝牙通话失败，默认路由会被启用。
             * 1359：音频设备模块：无采集设备。请检查是否有可用的录放音设备或者录放音设备是否已经被其他应用占用。
             * 1501：视频设备模块：没有摄像头使用权限。请检查是否已经打开摄像头权限。
             * 1600：视频设备模块：未知错误。
             * 1601：视频设备模块：视频编码器初始化错误。该错误为严重错误，请尝试重新加入频道。
             * 1602：视频设备模块：视频编码器错误。该错误为严重错误，请尝试重新加入频道。
             * 1603：视频设备模块：视频设置错误。
             */
            Log.d(TAG,"err:"+err);
        }

        @Override
        public void onJoinChannelSuccess(String channel, int uid, int elapsed) {
            super.onJoinChannelSuccess(channel, uid, elapsed);
            /**
             * channel	频道名
             * uid	用户 ID 。如果 joinChannel 中指定了 uid，则此处返回该 ID；否则使用 Agora 服务器自动分配的 ID
             * elapsed	从 joinChannel 开始到发生此事件过去的时间（毫秒)
             */
            Log.d(TAG,"onError(chanel:"+channel+"||"+"uid"+uid+"||"+"elapsed:"+elapsed+")");
        }

        @Override
        public void onRejoinChannelSuccess(String channel, int uid, int elapsed) {
            super.onRejoinChannelSuccess(channel, uid, elapsed);
            /**
             * channel	频道名
             * uid	用户 ID。如果 joinChannel 中指定了 uid，则此处返回该 ID；否则使用 Agora 服务器自动分配的 ID
             * elapsed	从调用 joinChannel 方法到触发该回调的时间间隔（毫秒）。
             */
            Log.d(TAG,"onRejoinChannelSuccess(chanel:"+channel+"||"+"uid"+uid+"||"+"elapsed:"+elapsed+")");
        }

        @Override
        public void onLeaveChannel(RtcStats stats) {
            super.onLeaveChannel(stats);

            Log.d(TAG,"onLeaveChannel+stats:"+stats+")");
        }

        @Override
        public void onClientRoleChanged(int oldRole, int newRole) {
            super.onClientRoleChanged(oldRole, newRole);
            /**
             * oldRole	切换前的角色：
             * CLIENT_ROLE_BROADCASTER(1)：主播。
             * CLIENT_ROLE_AUDIENCE(2)：观众。
             * newRole	切换后的角色：
             * CLIENT_ROLE_BROADCASTER(1)：主播。
             * CLIENT_ROLE_AUDIENCE(2)：观众。
             */
            Log.d(TAG,"onClientRoleChanged(oldRole:"+oldRole+"||"+"newRole:"+newRole+")");
        }

        @Override
        public void onLocalUserRegistered(int uid, String userAccount) {
            super.onLocalUserRegistered(uid, userAccount);
            /**
             * userAccount	本地用户的 User Account
             */
            Log.d(TAG,"onLocalUserRegistered(uid:"+uid+"||"+"userAccount:"+userAccount+")");
        }

        @Override
        public void onUserInfoUpdated(int uid, UserInfo userInfo) {
            super.onUserInfoUpdated(uid, userInfo);
            /**
             * userInfo	标识用户信息的 UserInfo 对象，包含用户 UID 和 User Account，详见 UserInfo 类
             */
            Log.d(TAG,"onUserInfoUpdated(uid:"+uid+"||"+"userInfo:"+userInfo+")");
        }

        @Override
        public void onUserJoined(int uid, int elapsed) {
            super.onUserJoined(uid, elapsed);
            /**
             * uid	新加入频道的远端用户/主播 ID
             * elapsed	从本地用户调用 joinChannel/setClientRole 到触发该回调的延迟（毫秒）
             */
            Log.d(TAG,"onUserJoined(uid:"+uid+"||"+"elapsed:"+elapsed+")");
        }

        @Override
        public void onUserOffline(int uid, int reason) {
            super.onUserOffline(uid, reason);
            /**
             * uid	主播 ID
             * reason	离线原因：
             * USER_OFFLINE_QUIT(0)：用户主动离开
             * USER_OFFLINE_DROPPED(1)：因过长时间收不到对方数据包，超时掉线。注意：由于 SDK 使用的是不可靠通道，也有可能对方主动离开本方没收到对方离开消息而误判为超时掉线
             * USER_OFFLINE_BECOME_AUDIENCE(2)：直播场景下，用户身份从主播切换为观众
             */
            Log.d(TAG,"onUserOffline(uid:"+uid+"||"+"reason:"+reason+")");
        }

        @Override
        public void onConnectionStateChanged(int state, int reason) {
            super.onConnectionStateChanged(state, reason);
            /**
             * 枚举值
             * CONNECTION_STATE_DISCONNECTED
             * 1: 网络连接断开。
             *
             * 该状态表示 SDK 处于:
             *
             * 调用 joinChannel 加入频道前的初始化阶段;
             * 或调用 leaveChannel 后的离开频道阶段。
             * CONNECTION_STATE_CONNECTING
             * 2: 建立网络连接中。
             *
             * 该状态表示 SDK 在调用 joinChannel 后正在与指定的频道建立连接。
             *
             * 如果成功加入频道，App 会收到 onConnectionStateChanged 回调，通知当前网络状态变成 CONNECTION_STATE_CONNECTED 。
             * 建立连接后，SDK 还会初始化媒体，一切就绪后会回调 onJoinChannelSuccess 。
             * CONNECTION_STATE_CONNECTED
             * 3: 网络已连接。
             *
             * 该状态表示用户已经加入频道，可以在频道内发布或订阅媒体流。
             *
             * 如果因网络断开或切换而导致 SDK 与频道的连接中断，SDK 会自动重连，此时应用程序会收到：
             *
             * onConnectionStateChanged 回调，通知当前网络状态变成 CONNECTION_STATE_RECONNECTING 。
             * 同时会收到 onConnectionInterrupted 回调（已废弃）。
             * CONNECTION_STATE_RECONNECTING
             * 4: 重新建立网络连接中。
             *
             * 该状态表示 SDK 之前曾加入过频道，但因网络等原因连接中断了，此时 SDK 会自动尝试重新接入频道。
             *
             * 如果 SDK 无法在 10 秒内重新加入频道，则 onConnectionLost 会被触发，SDK 会一直保持在 CONNECTION_STATE_RECONNECTING 的状态，并不断尝试重新加入频道。
             * 如果 SDK 在断开连接后，20 分钟内还是没能重新加入频道，则应用程序会收到 onConnectionStateChanged 回调，通知的网络状态进入 CONNECTION_STATE_FAILED ，SDK 停止尝试重连。
             * CONNECTION_STATE_FAILED
             * 5: 网络连接失败。
             *
             * 该状态表示 SDK 已不再尝试重新加入频道，用户必须要调用 leaveChannel 离开频道。
             *
             * 如果用户还想重新加入频道，则需要再次调用 joinChannel 。
             * 如果 SDK 因服务器端使用 RESTful API 禁止加入频道，则应用程序会收到 onConnectionBanned 回调（已废弃）和 onConnectionStateChanged 回调。
             *
             * CONNECTION_CHANGED_CONNECTING
             * 0: 建立网络连接中。
             *
             * CONNECTION_CHANGED_JOIN_SUCCESS
             * 1: 成功加入频道。
             *
             * CONNECTION_CHANGED_INTERRUPTED
             * 2: 网络连接中断。
             *
             * CONNECTION_CHANGED_BANNED_BY_SERVER
             * 3: 网络连接被服务器禁止。可能是由于服务端调用封禁用户权限 API 将用户踢出频道。请在应用中弹框提示“用户被踢出频道”。
             *
             * CONNECTION_CHANGED_JOIN_FAILED
             * 4: 加入频道失败。当 SDK 收到 onConnectionStateChanged(CONNECTION_STATE_RECONNECTING, CONNECTION_CHANGED_INTERRUPTED) 后连续 20 分钟无法重新加入频道时，会报告该状态并停止重连。 请在应用中弹框显示“因网络问题无法加入频道。请尝试切换网络并重新加入频道。”
             *
             * CONNECTION_CHANGED_LEAVE_CHANNEL
             * 5: 离开频道。
             *
             * CONNECTION_CHANGED_INVALID_APP_ID
             * 6: 不是有效的 APP ID。请更换有效的 APP ID 重新加入频道。请检查用户使用的 App ID 是否与从 Agora 控制台获取的项目的 App ID 一致，并更换有效的 APP ID 重新加入频道。
             *
             * CONNECTION_CHANGED_INVALID_CHANNEL_NAME
             * 7: 不是有效的频道名。频道名不能为空，且字符长度不能超过 64 字节，支持的字符集详见 joinChannel 的 channelName 参数描述。请在应用中弹框提示“频道名不合法。请更换有效的频道名并重新加入频道。”
             *
             * CONNECTION_CHANGED_INVALID_TOKEN
             * 8: Token 无效。一般有以下原因：
             *
             * 在控制台上启用了 App Certificate，但加入频道未使用 Token。当启用了 App Certificate，必须使用 Token。
             * 在调用 joinChannel 加入频道时指定的 uid 与生成 Token 时传入的 uid 不一致。
             * 请检查用户使用的 Token 是否与业务服务器上生成的 Token 一致，并更换有效的 Token 重新加入频道。
             *
             * CONNECTION_CHANGED_TOKEN_EXPIRED
             * 9: 当前使用的 Token 过期，用户被迫退出频道。客户端需要重新向自己的业务服务器申请 Token，并使用新的 Token 重新加入频道。
             *
             * CONNECTION_CHANGED_REJECTED_BY_SERVER
             * 10: 此用户被服务器禁止。一般有以下原因：
             *
             * 用户已进入频道，再次调用加入频道的 API，例如 joinChannel，会返回此状态。停止调用该方法即可。
             * 用户在调用 startEchoTest 进行通话测试时尝试加入频道。等待通话测试结束后再加入频道即可。
             * CONNECTION_CHANGED_SETTING_PROXY_SERVER
             * 11: 由于设置了代理服务器，SDK 尝试重连。
             *
             * CONNECTION_CHANGED_RENEW_TOKEN
             * 12: 更新 Token 引起网络连接状态改变。
             *
             * CONNECTION_CHANGED_CLIENT_IP_ADDRESS_CHANGED
             * 13: 由于网络类型，或网络运营商的 IP 或端口发生改变，客户端 IP 地址变更，SDK 尝试重连。如果多次出现该状态，请在应用中弹框提示“您的网络连接不稳定，建议切换网络。”
             *
             * CONNECTION_CHANGED_KEEP_ALIVE_TIMEOUT
             * 14: SDK 和服务器连接保活超时，进入自动重连状态 CONNECTION_STATE_RECONNECTING(4)。
             *
             * CONNECTION_CHANGED_SAME_UID_LOGIN
             * 19: 使用相同的 UID 从不同的设备加入同一频道。
             *
             * 自从
             * v3.7.0
             * CONNECTION_CHANGED_TOO_MANY_BROADCASTERS
             * 20: 频道内主播人数已达上限。
             *
             * 注解
             * 该枚举仅在开启 128 人功能后报告。主播人数的上限根据开启 128 人功能时实际配置的人数而定。
             * 自从
             * v3.7.0
             *
             *
             * */
            Log.d(TAG,"onConnectionStateChanged(state:"+state+"||"+"reason:"+reason+")");
        }

        @Override
        public void onConnectionLost() {
            super.onConnectionLost();
            /**
             * 网络连接中断，且 SDK 无法在 10 秒内连接服务器回调。
             *
             * SDK 在调用 joinChannel() 后，无论是否加入成功，只要 10 秒和服务器无法连接就会触发该回调。 与 onConnectionInterrupted 回调的区别是：
             *
             * onConnectionInterrupted 回调一定是发生在 joinChannel() 成功后，且 SDK 刚失去和服务器连接 4 秒时触发
             * onConnectionLost 回调是无论之前 joinChannel() 是否连接成功，只要 10 秒内和服务器无法建立连接都会触发
             * 如果 SDK 在断开连接后，20 分钟内还是没能重新加入频道，SDK 会停止尝试重连。
             */
            Log.d(TAG,"onConnectionLost(断连已经超过10秒了)");
        }


        @Override
        public void onApiCallExecuted(int error, String api, String result) {
            super.onApiCallExecuted(error, api, result);
            /**
             * error	错误码。如果方法调用失败，会返回错误码 Error code；如果返回 0，则表示方法调用成功
             * api	SDK 所调用的 API
             * result	SDK 调用 API 的调用结果
             */
            Log.d(TAG,"onApiCallExecuted（error:"+error+"||"+"api:"+api+"||"+"result:"+result+")");
        }

        @Override
        public void onTokenPrivilegeWillExpire(String token) {
            super.onTokenPrivilegeWillExpire(token);
            /**
             * Token 服务即将过期回调。
             *
             * 在调用 joinChannel 时如果指定了 Token，由于 Token 具有一定的时效，在通话过程中如果 Token 即将失效，SDK 会提前 30 秒触发该回调，提醒 App 更新 Token。当收到该回调时，你需要重新在服务端生成新的 Token，然后调用 renewToken 将新生成的 Token 传给 SDK。
             *
             * 参数
             * token	即将服务失效的 Token
             */
            Log.d(TAG,"onTokenPrivilegeWillExpire(token即将过期:"+token+")");
            if (listener != null) {
                token = listener.getNewToken();
            }
            engine.renewToken(token);
        }

        @Override
        public void onRequestToken() {
            super.onRequestToken();
            /**
             * Token 过期回调。
             *
             * 在调用 joinChannel 时如果指定了 Token，
             *
             * 由于 Token 具有一定的时效，在通话过程中 SDK 可能由于网络原因和服务器失去连接，重连时可能需要新的 Token。
             *
             * 该回调通知 App 需要生成新的 Token，并调用 renewToken 更新 Token。
             */
            Log.d(TAG,"onRequestToken(token已经过期)");
            if (listener != null) {
                token = listener.getNewToken();
            }
            engine.renewToken(token);
        }


    };

    /**
     * 状态回调
     */
    public interface Listener {

        void initError(String error);

        void startError(String error);

        String getNewToken();

    }

    // 是否支持原始h264输出
    private boolean isTransCodedVideoFeedNeeded() {
        if (VideoFeeder.getInstance() == null) {
            return false;
        }

        return VideoFeeder.getInstance().isFetchKeyFrameNeeded() || VideoFeeder.getInstance()
                .isLensDistortionCalibrationNeeded();
    }


}


