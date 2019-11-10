/*
 * Copyright (c) 2014-present, Facebook, Inc. All rights reserved.
 *
 * You are hereby granted a non-exclusive, worldwide, royalty-free license to use,
 * copy, modify, and distribute this software in source code or binary form for use
 * in connection with the web services and APIs provided by Facebook.
 *
 * As with any software that integrates with the Facebook platform, your use of
 * this software is subject to the Facebook Developer Principles and Policies
 * [http://developers.facebook.com/policy/]. This copyright notice shall be
 * included in all copies or substantial portions of the software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.facebook.appevents.ml;

import android.content.Context;
import android.os.AsyncTask;
import android.support.annotation.Nullable;
import android.util.Log;

import com.facebook.FacebookSdk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

final class Model {
    private static final String DIR_NAME = "facebook_ml/";

    private String useCase;
    private File modelFile;
    private int versionID;
    @Nullable private String urlStr;

    @Nullable private static float[] embedding = null;
    @Nullable private static float[] convs_1_weight = null;
    @Nullable private static float[] convs_2_weight = null;
    @Nullable private static float[] convs_3_weight = null;
    @Nullable private static float[] convs_1_bias = null;
    @Nullable private static float[] convs_2_bias = null;
    @Nullable private static float[] convs_3_bias = null;
    @Nullable private static float[] fc1_weight = null;
    @Nullable private static float[] fc2_weight = null;
    @Nullable private static float[] fc3_weight = null;
    @Nullable private static float[] fc1_bias = null;
    @Nullable private static float[] fc2_bias = null;
    @Nullable private static float[] fc3_bias = null;

    Model(String useCase, int versionID) {
        this.useCase = useCase;
        this.versionID = versionID;
        String filePath = DIR_NAME + useCase + "_" + versionID;
        this.modelFile = new File(FacebookSdk.getApplicationContext().getFilesDir(), filePath);
    }

    Model(String useCase, int versionID, String urlStr) {
        this(useCase, versionID);
        this.urlStr = urlStr;
    }

    void initialize(final Runnable onModelInitialized) {
        Runnable onSucess = new Runnable() {
            @Override
            public void run() {
                if (initializeWeights()) {
                    onModelInitialized.run();
                }
            }
        };

        if (modelFile.exists()) {
            onSucess.run();
        } else {
            download(onSucess);
        }
    }

    // return true if weights initialized successful
    private boolean initializeWeights() {
        // TODO: (@linajin T57235101) make it more general and support other use cases
        try {
            InputStream inputStream = new FileInputStream(modelFile);
            int length = inputStream.available();
            DataInputStream dataIs = new DataInputStream(inputStream);
            byte[] allData = new byte[length];
            dataIs.readFully(allData);
            dataIs.close();

            if (length < 4) {
                return false;
            }

            ByteBuffer bb = ByteBuffer.wrap(allData, 0, 4);
            bb.order(ByteOrder.LITTLE_ENDIAN);
            int jsonLen =  bb.getInt();

            if (length < jsonLen + 4) {
                return false;
            }

            String jsonStr = new String(allData, 4, jsonLen);
            JSONObject info = new JSONObject(jsonStr);

            JSONArray names = info.names();
            String[] keys = new String[names.length()];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = names.getString(i);
            }
            Arrays.sort(keys);

            int offset = 4 + jsonLen;

            Map<String, Weight> weights = new HashMap<>();

            for (String key : keys) {
                int count = 1;
                JSONArray shapes = info.getJSONArray(key);
                int[] shape = new int[shapes.length()];
                for (int i = 0; i < shape.length; i++)  {
                    shape[i] = shapes.getInt(i);
                    count *= shape[i];
                }

                if (offset + count * 4 > length) {
                    return false;
                }

                bb = ByteBuffer.wrap(allData, offset, count * 4);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                float[] data = new float[count];
                bb.asFloatBuffer().get(data, 0, count);
                weights.put(key, new Weight(shape, data));
                offset += count * 4;
            }

            embedding = weights.get("embed.weight").data;
            convs_1_weight = weights.get("convs.0.weight").data;
            convs_2_weight = weights.get("convs.1.weight").data;
            convs_3_weight = weights.get("convs.2.weight").data;
            convs_1_bias = weights.get("convs.0.bias").data;
            convs_2_bias = weights.get("convs.1.bias").data;
            convs_3_bias = weights.get("convs.2.bias").data;
            fc1_weight = weights.get("fc1.weight").data;
            fc2_weight = weights.get("fc2.weight").data;
            fc3_weight = weights.get("fc3.weight").data;
            fc1_bias = weights.get("fc1.bias").data;
            fc2_bias = weights.get("fc2.bias").data;
            fc3_bias = weights.get("fc3.bias").data;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    String predict(float[] dense, String text) {
        // TODO: (christina1012: T54293420) hook with JNI
        return "";
    }

    private void download(Runnable onSuccess) {
        if (urlStr == null) {
            return;
        }
        String[] args = new String[] {urlStr};
        new FileDownloadTask(modelFile, onSuccess).execute(args);
    }

    static class FileDownloadTask extends AsyncTask<String, Void, Boolean> {
        Runnable onSuccess;
        File destFile;
        FileDownloadTask(File destFile, Runnable onSuccess) {
            this.destFile = destFile;
            this.onSuccess = onSuccess;
        }

        @Override
        protected Boolean doInBackground(String... args) {
            try {
                String urlStr = args[0];
                Context context = FacebookSdk.getApplicationContext();

                File dir = new File(context.getFilesDir(), DIR_NAME);
                if (!dir.exists()) {
                    dir.mkdirs();
                }

                URL url = new URL(urlStr);
                URLConnection conn = url.openConnection();
                int contentLength = conn.getContentLength();

                DataInputStream stream = new DataInputStream(url.openStream());

                byte[] buffer = new byte[contentLength];
                stream.readFully(buffer);
                stream.close();

                DataOutputStream fos = new DataOutputStream(new FileOutputStream(destFile));
                fos.write(buffer);
                fos.flush();
                fos.close();
                return true;
            } catch (Exception e) {
                /** no op **/
            }
            return false;
        }

        @Override
        protected void onPostExecute(Boolean isSuccess) {
            if (isSuccess) {
                onSuccess.run();
            }
        }
    }

    private static class Weight {
        final public int[] shape;
        final public float[] data;

        Weight(int[] shape, float[] data)  {
            this.shape = shape;
            this.data = data;
        }
    }
}
