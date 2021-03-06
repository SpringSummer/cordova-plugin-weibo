package com.giants.weibo;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;

import com.sina.weibo.sdk.api.ImageObject;
import com.sina.weibo.sdk.api.TextObject;
import com.sina.weibo.sdk.api.WebpageObject;
import com.sina.weibo.sdk.api.WeiboMultiMessage;
import com.sina.weibo.sdk.api.share.IWeiboShareAPI;
import com.sina.weibo.sdk.api.share.SendMultiMessageToWeiboRequest;
import com.sina.weibo.sdk.api.share.WeiboShareSDK;
import com.sina.weibo.sdk.auth.AuthInfo;
import com.sina.weibo.sdk.auth.Oauth2AccessToken;
import com.sina.weibo.sdk.auth.WeiboAuthListener;
import com.sina.weibo.sdk.auth.sso.SsoHandler;
import com.sina.weibo.sdk.exception.WeiboException;
import com.sina.weibo.sdk.utils.Utility;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

public class Weibo extends CordovaPlugin {

    private static final String SCOPE = "email,direct_messages_read,direct_messages_write,"
            + "friendships_groups_read,friendships_groups_write,statuses_to_me_read,"
            + "follow_app_official_microblog," + "invitation_write";
    private static final String WEBIO_APP_ID = "weibo_app_id";
    private static final String WEBIO_REDIRECT_URL = "redirecturi";
    private static final String DEFAULT_URL = "https://api.weibo.com/oauth2/default.html";
    private static final String CANCEL_BY_USER = "cancel by user";
    private static final String UNKONW_ERROR = "unkonw error";
    private static final String WEIBO_EXCEPTION = "weibo exception";
    private static final String ONLY_GET_CODE = "only get code";
    private static final String ERROR_IMAGE_URL = "share image url is incorrect";
    private static final String WEIBO_CLIENT_NOT_INSTALLED = "您尚未安装微博客户端";
    private static final String DEFAULT_WEBPAGE_ICON = "http://www.sinaimg.cn/blog/developer/wiki/LOGO_64x64.png";
    public static CallbackContext currentCallbackContext;
    public static String APP_KEY;
    public static IWeiboShareAPI mWeiboShareAPI = null;
    private Oauth2AccessToken mAccessToken;
    private String REDIRECT_URL;
    private SsoHandler mSsoHandler;

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();
        APP_KEY = webView.getPreferences().getString(WEBIO_APP_ID, "");
        REDIRECT_URL = webView.getPreferences().getString(WEBIO_REDIRECT_URL, DEFAULT_URL);
    }

    @Override
    public boolean execute(String action, final JSONArray args,
                           final CallbackContext callbackContext) throws JSONException {
        if (action.equalsIgnoreCase("ssoLogin")) {
            return ssoLogin(callbackContext);
        } else if (action.equalsIgnoreCase("logout")) {
            return logout(callbackContext);
        } else if (action.equalsIgnoreCase("shareToWeibo")) {
            return shareToWeibo(callbackContext, args);
        } else if (action.equalsIgnoreCase("checkClientInstalled")) {
            return checkClientInstalled(callbackContext);
        }
        return super.execute(action, args, callbackContext);
    }

    /**
     * 组装JSON
     *
     * @param access_token
     * @param userid
     * @param expires_time
     * @return
     */
    private JSONObject makeJson(String access_token, String userid, long expires_time) {
        String json = "{\"access_token\": \"" + access_token + "\", " +
                " \"userid\": \"" + userid + "\", " +
                " \"expires_time\": \"" + String.valueOf(expires_time) + "\"" +
                "}";
        JSONObject jo = null;
        try {
            jo = new JSONObject(json);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jo;
    }

    /**
     * weibo sso 登录
     *
     * @param callbackContext
     * @return
     */
    private boolean ssoLogin(CallbackContext callbackContext) {
        currentCallbackContext = callbackContext;
        AuthInfo mAuthInfo = new AuthInfo(Weibo.this.cordova.getActivity(),
                APP_KEY, REDIRECT_URL, SCOPE);
        mSsoHandler = new SsoHandler(Weibo.this.cordova.getActivity(),
                mAuthInfo);
        mAccessToken = AccessTokenKeeper.readAccessToken(Weibo.this.cordova
                .getActivity());
        if (mAccessToken.isSessionValid()) {
            JSONObject jo = makeJson(mAccessToken.getToken(),
                    mAccessToken.getUid(), mAccessToken.getExpiresTime());
            this.webView.sendPluginResult(new PluginResult(
                    PluginResult.Status.OK, jo), callbackContext
                    .getCallbackId());
        } else {
            Runnable runnable = new Runnable() {
                public void run() {
                    if (mSsoHandler != null) {
                        mSsoHandler.authorize(AuthListener);
                    }
                }

                ;
            };
            this.cordova.setActivityResultCallback(this);
            this.cordova.getActivity().runOnUiThread(runnable);
        }
        return true;
    }

    /**
     * 检查微博客户端是否安装
     *
     * @param callbackContext
     * @return
     */
    private boolean checkClientInstalled(CallbackContext callbackContext) {
        AuthInfo mAuthInfo = new AuthInfo(Weibo.this.cordova.getActivity(),
                APP_KEY, REDIRECT_URL, SCOPE);
        if (mSsoHandler == null) {
            mSsoHandler = new SsoHandler(Weibo.this.cordova.getActivity(),
                    mAuthInfo);
        }
        Boolean installed = mSsoHandler.isWeiboAppInstalled();
        if (installed) {
            callbackContext.success();
        } else {
            callbackContext.error(WEIBO_CLIENT_NOT_INSTALLED);
        }
        return true;
    }

    /**
     * 微博登出
     *
     * @param callbackContext
     * @return
     */
    private boolean logout(CallbackContext callbackContext) {
        AccessTokenKeeper.clear(this.cordova.getActivity());
        callbackContext.success();
        return true;
    }

    /**
     * 微博分享
     *
     * @param callbackContext
     * @param args
     * @return
     */
    private boolean shareToWeibo(CallbackContext callbackContext,
                                 final JSONArray args) {
        currentCallbackContext = callbackContext;
        mWeiboShareAPI = WeiboShareSDK.createWeiboAPI(
                this.cordova.getActivity(), APP_KEY);
        mWeiboShareAPI.registerApp();
        cordova.getThreadPool().execute(new Runnable() {

            @Override
            public void run() {
                try {
                    sendSingleMessage(args.getJSONObject(0));
                } catch (JSONException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        });
        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (mSsoHandler != null) {
            mSsoHandler.authorizeCallBack(requestCode, resultCode, intent);
        }

    }

    /**
     * 生成微博要分享出去的网页对象
     *
     * @param params
     * @return
     * @throws JSONException
     */
    private WebpageObject getWebpageObj(JSONObject params) throws JSONException {
        WebpageObject mediaObject = new WebpageObject();
        mediaObject.identify = Utility.generateGUID();
        mediaObject.title = "";
        mediaObject.description = params.getString("description");
        String url = params.getString("imageUrl");
        if (url != null
                && !url.equalsIgnoreCase("imageUrl")) {
                if (url.startsWith("http://")
                        || url.startsWith("https://")) {
                    Bitmap thumb = returnBitMap(url,"webPage");

                    mediaObject.setThumbImage(thumb);
                } else {
                    currentCallbackContext.error(ERROR_IMAGE_URL);
                }

        } else {
            try {
                Bitmap thumb = BitmapFactory
                        .decodeStream(new URL(DEFAULT_WEBPAGE_ICON)
                                .openConnection().getInputStream());
                mediaObject.setThumbImage(thumb);
            } catch (MalformedURLException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        mediaObject.actionUrl = params.getString("url");
        mediaObject.defaultText = params.getString("defaultText");
        return mediaObject;
    }

    /**
     * 获取网络图片转换为bitmap
     * @param url
     * @return
     */
    public Bitmap returnBitMap(String url,String cate) {
        URL myFileUrl = null;
        Bitmap bitmap = null;
        try {
            myFileUrl = new URL(url);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) myFileUrl.openConnection();
            conn.setDoInput(true);
            conn.connect();
            InputStream is = conn.getInputStream();
            bitmap = BitmapFactory.decodeStream(is);
            is.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if(cate.equalsIgnoreCase("webPage")){
            return imageZoom(bitmap);
        }else {
            return bitmap;
        }

    }

    /**
     * 压缩图片
     * @param bitMap
     * @return
     */
    private Bitmap imageZoom(Bitmap bitMap) {
        //图片允许最大空间   单位：KB
        double maxSize =40.00;
        //将bitmap放至数组中，意在bitmap的大小（与实际读取的原文件要大）
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitMap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
        byte[] b = baos.toByteArray();
        //将字节换成KB
        double mid = b.length/1024;
        //判断bitmap占用空间是否大于允许最大空间  如果大于则压缩 小于则不压缩
        if (mid > maxSize) {
            //获取bitmap大小 是允许最大大小的多少倍
            double i = mid / maxSize;
            //开始压缩  此处用到平方根 将宽带和高度压缩掉对应的平方根倍 （1.保持刻度和高度和原bitmap比率一致，压缩后也达到了最大大小占用空间的大小）
            bitMap = zoomImage(bitMap, bitMap.getWidth() / Math.sqrt(i),
                    bitMap.getHeight() / Math.sqrt(i));
        }
        return bitMap;
    }

    /***
     * 图片的缩放方法
     *
     * @param bgimage
     *            ：源图片资源
     * @param newWidth
     *            ：缩放后宽度
     * @param newHeight
     *            ：缩放后高度
     * @return
     */
    public  Bitmap zoomImage(Bitmap bgimage, double newWidth,
                             double newHeight) {
        // 获取这个图片的宽和高
        float width = bgimage.getWidth();
        float height = bgimage.getHeight();
        // 创建操作图片用的matrix对象
        Matrix matrix = new Matrix();
        // 计算宽高缩放率
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        // 缩放图片动作
        matrix.postScale(scaleWidth, scaleHeight);
        Bitmap bitmap = Bitmap.createBitmap(bgimage, 0, 0, (int) width,
                (int) height, matrix, true);
        return  imageZoom(bitmap);
    }
    /**
     * 创建文本消息对象。
     *
     * @return 文本消息对象。
     */
    private TextObject getTextObj(String text) {
        TextObject textObject = new TextObject();
        textObject.text = text + "（分享自 @CB自行车网 客户端）链接";
        return textObject;
    }

    /**
     * 创建图片消息对象。
     *
     * @return 图片消息对象。
     */
    private ImageObject getImageObj(String url) {

        ImageObject imageObject = new ImageObject();
        //BitmapDrawable bitmapDrawable = (BitmapDrawable) mImageView.getDrawable();
        //设置缩略图。 注意：最终压缩过的缩略图大小不得超过 32kb。
        Bitmap  bitmap = returnBitMap(url,"imageObject");
        imageObject.setImageObject(bitmap);
        return imageObject;
    }
    /**
     * 发送微博单条消息请求
     *
     * @param params
     */
    private void sendSingleMessage(JSONObject params) throws JSONException {
        WeiboMultiMessage weiboMessage = new WeiboMultiMessage();
       String title =  params.getString("title");
        String  imageUrl  = params.getString("imageUrl");

        try {
            weiboMessage.textObject = getTextObj(title);
            weiboMessage.imageObject = getImageObj(imageUrl);
            weiboMessage.mediaObject = getWebpageObj(params);
        } catch (JSONException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        SendMultiMessageToWeiboRequest request = new SendMultiMessageToWeiboRequest();
        request.transaction = String.valueOf(System.currentTimeMillis());
        request.multiMessage = weiboMessage;
        if (mWeiboShareAPI.isWeiboAppInstalled()) {
            if (mWeiboShareAPI.isWeiboAppSupportAPI()) {
                int supportApi = mWeiboShareAPI.getWeiboAppSupportAPI();
                Log.e("supportApi",String.valueOf(supportApi));
               Boolean flag = mWeiboShareAPI.sendRequest(this.cordova.getActivity(), request);
                //currentCallbackContext.error(flag.toString());
            } else {
                currentCallbackContext.error(UNKONW_ERROR);
            }
        } else {
            currentCallbackContext.error(WEIBO_CLIENT_NOT_INSTALLED);
        }
    }

    /**
     * 微博auth监听
     */
    WeiboAuthListener AuthListener = new WeiboAuthListener() {

        @Override
        public void onComplete(Bundle values) {
            mAccessToken = Oauth2AccessToken.parseAccessToken(values);
            if (mAccessToken.isSessionValid()) {
                AccessTokenKeeper.writeAccessToken(
                        Weibo.this.cordova.getActivity(), mAccessToken);
                JSONObject jo = makeJson(mAccessToken.getToken(),
                        mAccessToken.getUid(),mAccessToken.getExpiresTime());
                Weibo.this.webView.sendPluginResult(new PluginResult(
                        PluginResult.Status.OK, jo), currentCallbackContext.getCallbackId());
            } else {
                // 以下几种情况，您会收到 Code：
                // 1. 当您未在平台上注册的应用程序的包名与签名时；
                // 2. 当您注册的应用程序包名与签名不正确时；
                // 3. 当您在平台上注册的包名和签名与您当前测试的应用的包名和签名不匹配时。
                 //String code = values.getString("code");
                Weibo.this.webView.sendPluginResult(new PluginResult(
                                PluginResult.Status.ERROR, ONLY_GET_CODE),
                        currentCallbackContext.getCallbackId());

            }
        }

        @Override
        public void onCancel() {
            Weibo.this.webView.sendPluginResult(new PluginResult(
                            PluginResult.Status.ERROR, CANCEL_BY_USER),
                    currentCallbackContext.getCallbackId());
        }

        @Override
        public void onWeiboException(WeiboException e) {
            Weibo.this.webView.sendPluginResult(new PluginResult(
                            PluginResult.Status.ERROR, WEIBO_EXCEPTION),
                    currentCallbackContext.getCallbackId());
        }
    };
}
