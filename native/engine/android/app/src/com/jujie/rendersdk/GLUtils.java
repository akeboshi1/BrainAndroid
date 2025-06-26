package com.jujie.rendersdk;

import android.opengl.GLES20;
import android.opengl.GLES11Ext;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import android.graphics.Bitmap;

public class GLUtils {
    private static final String TAG = "GLUtils";

    // 顶点着色器源码
    public static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}";

    // 片元着色器源码
    public static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D uTexture;\n" +
            "void main() {\n" +
            "    vec4 texColor = texture2D(uTexture, vTexCoord);\n" +
            "    // 强制设置透明度为0.5进行测试\n" +
            "    gl_FragColor = vec4(texColor.rgb, texColor.a * 0.5);\n" +
            "}";

    // OES片元着色器源码
    public static final String OES_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}";

    // 编译着色器
    public static int compileShader(int type, String shaderSource) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderSource);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader " + type + ":");
            Log.e(TAG, GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    // 链接OpenGL程序
    public static int linkProgram(int vertexShader, int fragmentShader) {
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ");
            Log.e(TAG, GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        return program;
    }

    // 创建FBO
    public static int createFBO(int textureId) {
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textureId, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer not complete, status: " + status);
            GLES20.glDeleteFramebuffers(1, fbo, 0);
            return 0;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return fbo[0];
    }

    // 创建2D纹理
    public static int createTexture2D(int width, int height) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return tex[0];
    }

    // 删除FBO
    public static void deleteFBO(int fboId) {
        int[] fbo = new int[]{fboId};
        GLES20.glDeleteFramebuffers(1, fbo, 0);
    }

    // 删除纹理
    public static void deleteTexture(int texId) {
        int[] tex = new int[]{texId};
        GLES20.glDeleteTextures(1, tex, 0);
    }

    // 创建FloatBuffer
    public static FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    // 通用绘制方法：将纹理绘制到当前FBO或屏幕
    public static void drawTexture(int program, int textureId, float[] vertexCoords, float[] texCoords) {
        int aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        int aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        int uTexture = GLES20.glGetUniformLocation(program, "uTexture");

        FloatBuffer vertexBuffer = createFloatBuffer(vertexCoords);
        FloatBuffer texBuffer = createFloatBuffer(texCoords);

        GLES20.glUseProgram(program);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(uTexture, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES20.glUseProgram(0);
    }

    // 用于采样OES纹理（GL_TEXTURE_EXTERNAL_OES）并绘制
    public static void drawOESTexture(int program, int textureId, float[] vertexCoords, float[] texCoords) {
        int aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        int aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        int uTexture = GLES20.glGetUniformLocation(program, "uTexture");

        FloatBuffer vertexBuffer = createFloatBuffer(vertexCoords);
        FloatBuffer texBuffer = createFloatBuffer(texCoords);

        GLES20.glUseProgram(program);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(uTexture, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES20.glUseProgram(0);
    }

    // 用于采样2D纹理（GL_TEXTURE_2D）并绘制
    public static void draw2DTexture(int program, int textureId, float[] vertexCoords, float[] texCoords) {
        int aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        int aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        int uTexture = GLES20.glGetUniformLocation(program, "uTexture");

        FloatBuffer vertexBuffer = createFloatBuffer(vertexCoords);
        FloatBuffer texBuffer = createFloatBuffer(texCoords);

        // 启用混合模式以支持透明度
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glUseProgram(program);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(uTexture, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(0);
        
        // 禁用混合模式
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // 将 Bitmap 加载为 OpenGL 2D 纹理，返回纹理ID
    public static int createTextureFromBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            Log.e(TAG, "createTextureFromBitmap: bitmap is null");
            return -1;
        }
        
        Log.d(TAG, "createTextureFromBitmap: bitmap size = " + bitmap.getWidth() + "x" + bitmap.getHeight());
        
        // 检查OpenGL上下文
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "createTextureFromBitmap: OpenGL error before starting: " + error);
            GLES20.glGetError(); // 清除错误
        }
        
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        
        error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "createTextureFromBitmap: glGenTextures error = " + error);
            return -1;
        }
        
        if (tex[0] == 0) {
            Log.e(TAG, "createTextureFromBitmap: glGenTextures returned 0");
            return -1;
        }
        
        Log.d(TAG, "createTextureFromBitmap: generated texture ID = " + tex[0]);
        
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        
        // 检查OpenGL错误
        error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "createTextureFromBitmap: glBindTexture error = " + error);
            GLES20.glDeleteTextures(1, tex, 0);
            return -1;
        }
        
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        
        error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "createTextureFromBitmap: glTexParameteri error = " + error);
            GLES20.glDeleteTextures(1, tex, 0);
            return -1;
        }
        
        try {
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            
            // 检查OpenGL错误
            error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "createTextureFromBitmap: texImage2D error = " + error);
                GLES20.glDeleteTextures(1, tex, 0);
                return -1;
            }
            
            Log.d(TAG, "createTextureFromBitmap: texture loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "createTextureFromBitmap: texImage2D exception", e);
            GLES20.glDeleteTextures(1, tex, 0);
            return -1;
        }
        
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return tex[0];
    }

    // 新增：RGBA 转 YUV420 方法
    public static byte[] rgbaToYuv420(byte[] rgba, int width, int height) {
        int frameSize = width * height;
        byte[] yuv = new byte[frameSize * 3 / 2];
        
        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = frameSize + frameSize/4;
        
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int rgbaIndex = (j * width + i) * 4;
                int r = rgba[rgbaIndex] & 0xff;
                int g = rgba[rgbaIndex + 1] & 0xff;
                int b = rgba[rgbaIndex + 2] & 0xff;
                
                // RGB to Y
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yuv[yIndex++] = (byte) (y < 0 ? 0 : (y > 255 ? 255 : y));
                
                // RGB to U,V (每2x2像素采样一次)
                if (j % 2 == 0 && i % 2 == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    yuv[uIndex++] = (byte) (u < 0 ? 0 : (u > 255 ? 255 : u));
                    yuv[vIndex++] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
                }
            }
        }
        return yuv;
    }

    // 新增：创建像素缓冲区
    public static ByteBuffer createDirectBuffer(int size) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        buffer.order(ByteOrder.nativeOrder());
        return buffer;
    }
}
