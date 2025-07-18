package com.cocos.game;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;
import android.util.Log;
import com.cocos.lib.JsbBridge;

public class PostVideoData {
    // 上传视频并获取评分，出错返回60
    public static Result postVideoForScore(File videoFile, String activity_id, String token, int group_size) {
        Log.d("PostVideoData", "开始上传视频评分 - 文件: " + videoFile.getName() + ", 大小: " + videoFile.length() + " bytes");
        Log.d("PostVideoData", "参数 - activity_id: " + activity_id + ", token: " + token + ", group_size: " + group_size);
        
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        String LINE_FEED = "\r\n";
        int score = 60; // 默认分数
        String filename = "";
        HttpURLConnection conn = null;
        DataOutputStream outputStream = null;
        BufferedReader reader = null;
        try {
            URL url = new URL("https://test.paipai2.xinjiaxianglao.com/api/finger_exercise/upload_and_evaluate");
            Log.d("PostVideoData", "连接URL: " + url.toString());
            
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            
            Log.d("PostVideoData", "开始写入请求数据...");
            outputStream = new DataOutputStream(conn.getOutputStream());
            
            // 写入 activity_id 参数
            String taskIdPart = "--" + boundary + LINE_FEED
                    + "Content-Disposition: form-data; name=\"activity_id\"" + LINE_FEED
                    + LINE_FEED
                    + activity_id + LINE_FEED;
            outputStream.writeBytes(taskIdPart);
            
            // 写入 token 参数
            String tokenPart = "--" + boundary + LINE_FEED
                    + "Content-Disposition: form-data; name=\"token\"" + LINE_FEED
                    + LINE_FEED
                    + token + LINE_FEED;
            outputStream.writeBytes(tokenPart);
            
            // 写入 group_size 参数
            String groupSizePart = "--" + boundary + LINE_FEED
                    + "Content-Disposition: form-data; name=\"group_size\"" + LINE_FEED
                    + LINE_FEED
                    + group_size + LINE_FEED;
            outputStream.writeBytes(groupSizePart);
            
            // 写入文件部分
            String filePartHeader = "--" + boundary + LINE_FEED
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + videoFile.getName() + "\"" + LINE_FEED
                    + "Content-Type: video/mp4" + LINE_FEED
                    + LINE_FEED;
            outputStream.writeBytes(filePartHeader);
            
            Log.d("PostVideoData", "开始写入视频文件数据...");
            // 写入文件内容
            FileInputStream fileInputStream = new FileInputStream(videoFile);
            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalBytesRead = 0;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
            }
            fileInputStream.close();
            Log.d("PostVideoData", "视频文件写入完成，总字节数: " + totalBytesRead);
            
            outputStream.writeBytes(LINE_FEED);
            // 结束分隔符
            outputStream.writeBytes("--" + boundary + "--" + LINE_FEED);
            outputStream.flush();
            
            Log.d("PostVideoData", "请求数据写入完成，开始发送请求...");

            // 读取响应
            int responseCode = conn.getResponseCode();
            Log.d("PostVideoData", "服务器响应码: " + responseCode);
            
            InputStream is = (responseCode == 200) ? conn.getInputStream() : conn.getErrorStream();
            reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            
            String responseStr = response.toString();
            Log.d("PostVideoData", "服务器响应内容: " + responseStr);
            
            // 解析JSON
            JSONObject json = new JSONObject(responseStr);
            if (json.optInt("status") == 1) {
                JSONObject data = json.optJSONObject("data");
                if (data != null) {
                    int avgLeftScore = data.optInt("avg_left_score", 60);
                    int avgRightScore = data.optInt("avg_right_score", 60);
                    String taskId = data.optString("activity_id", "");
                    filename = data.optString("filename", "");
                    
                    Log.d("PostVideoData", "解析成功 - avgLeftScore: " + avgLeftScore + ", avgRightScore: " + avgRightScore);
                    Log.d("PostVideoData", "解析成功 - taskId: " + taskId + ", filename: " + filename);
                    
                    // 解析每组得分数据
                    GroupScore[] groups = new GroupScore[0];
                    if (data.has("groups")) {
                        org.json.JSONArray groupsArray = data.optJSONArray("groups");
                        if (groupsArray != null) {
                            groups = new GroupScore[groupsArray.length()];
                            Log.d("PostVideoData", "开始解析 " + groupsArray.length() + " 组得分数据");
                            for (int i = 0; i < groupsArray.length(); i++) {
                                JSONObject groupObj = groupsArray.optJSONObject(i);
                                if (groupObj != null) {
                                    int seq = groupObj.optInt("seq", i + 1);
                                    int leftScore = groupObj.optInt("left_score", 60);
                                    int rightScore = groupObj.optInt("right_score", 60);
                                    groups[i] = new GroupScore(seq, leftScore, rightScore);
                                    Log.d("PostVideoData", "第" + seq + "组: 左手" + leftScore + ", 右手" + rightScore);
                                }
                            }
                        }
                    }
                    
                    Log.d("PostVideoData", "视频评分上传成功");
                    JsbBridge.sendToScript("POSTVIDEODATAFINISHED", responseStr);
                    return new Result(avgLeftScore, avgRightScore, taskId, filename, groups);
                }
            } else {
                // 服务器返回错误
                Log.d("PostVideoData", "服务器返回错误状态: " + json.optString("message", "评估失败"));
                throw new Exception(json.optString("message", "评估失败"));
            }
        } catch (Exception e) {
            Log.d("PostVideoData", "POSTVIDEODATAERROR: " + e.getMessage());
            Log.e("PostVideoData", "视频上传失败", e);
            JsbBridge.sendToScript("POSTVIDEODATAERROR", "");
            // 返回默认分数60和空文件名
        } finally {
            try { if (outputStream != null) outputStream.close(); } catch (Exception ignore) {}
            try { if (reader != null) reader.close(); } catch (Exception ignore) {}
            if (conn != null) conn.disconnect();
            Log.d("PostVideoData", "网络连接已关闭");
        }
        return new Result(60, "");
    }

    // 上传内存中的mp4数据并获取评分和文件名，出错返回默认分数和空文件名
    public static class Result {
        private int avgLeftScore;
        private int avgRightScore;
        private String taskId;
        private String filename;
        private GroupScore[] groups;
        
        public Result(int avgLeftScore, int avgRightScore, String taskId, String filename, GroupScore[] groups) {
            this.avgLeftScore = avgLeftScore;
            this.avgRightScore = avgRightScore;
            this.taskId = taskId;
            this.filename = filename;
            this.groups = groups;
        }
        
        // 为了向后兼容，保留原有的构造函数
        public Result(int score, String filename) {
            this.avgLeftScore = score;
            this.avgRightScore = score;
            this.taskId = "";
            this.filename = filename;
            this.groups = new GroupScore[0];
        }
        
        public int getAvgLeftScore() { return avgLeftScore; }
        public int getAvgRightScore() { return avgRightScore; }
        public String getTaskId() { return taskId; }
        public String getFilename() { return filename; }
        public GroupScore[] getGroups() { return groups; }
        
        // 为了向后兼容，保留原有的getScore方法
        public int getScore() { return avgLeftScore; }
    }
    
    // 每组得分数据类
    public static class GroupScore {
        private int seq;
        private int leftScore;
        private int rightScore;
        
        public GroupScore(int seq, int leftScore, int rightScore) {
            this.seq = seq;
            this.leftScore = leftScore;
            this.rightScore = rightScore;
        }
        
        public int getSeq() { return seq; }
        public int getLeftScore() { return leftScore; }
        public int getRightScore() { return rightScore; }
    }
}
