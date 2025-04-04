package io.jenkins.plugins.remote.result.trigger.utils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import hudson.model.BuildableItem;
import hudson.model.Item;
import io.jenkins.plugins.remote.result.trigger.RemoteJenkinsServer;
import io.jenkins.plugins.remote.result.trigger.RemoteJobInfo;
import io.jenkins.plugins.remote.result.trigger.exceptions.UnSuccessfulRequestStatusException;
import io.jenkins.plugins.remote.result.trigger.model.SavedJobInfo;
import io.jenkins.plugins.remote.result.trigger.utils.ssl.SSLSocketManager;
import okhttp3.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.lang.NonNull;

import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Remote Result Result Cache
 *
 * @author HW
 */
public class RemoteJobResultUtils {
    /**
     * get remote job next build number
     *
     * @param job     Jenkins job
     * @param jobInfo remote Job info
     * @return 下一个版本
     */
    public static Integer requestNextBuildNumber(Item job, RemoteJobInfo jobInfo)
            throws UnSuccessfulRequestStatusException, IOException {
        String api = jobInfo.getRemoteJobUrl() + "/api/json";
        SourceMap result = requestRemoteApi(job, jobInfo, api);
        if (result != null) {
            return result.integerValue("nextBuildNumber");
        }
        return null;
    }

    /**
     * get remote job result
     *
     * @param job     Jenkins job
     * @param jobInfo remote Job info
     * @param number  build number
     * @return api result
     */
    public static SourceMap requestBuildResult(Item job, RemoteJobInfo jobInfo, int number)
            throws UnSuccessfulRequestStatusException, IOException {
        String api = jobInfo.getRemoteJobUrl() + "/" + number + "/api/json";
        return requestRemoteApi(job, jobInfo, api);
    }

    /**
     * last checked build number
     *
     * @param job     Jenkins job
     * @param jobInfo remote Job info
     * @return last trigger number
     */
    public static int getCheckedNumber(Item job, RemoteJobInfo jobInfo) throws IOException {
        SavedJobInfo savedJobInfo = getSavedJobInfo(job, jobInfo);
        // 兼容老版本
        if (savedJobInfo != null
                && (savedJobInfo.getCheckedNumber() != null || savedJobInfo.getTriggerNumber() != null)) {
            return savedJobInfo.getCheckedNumber() == null ?
                    savedJobInfo.getTriggerNumber() : savedJobInfo.getCheckedNumber();
        }
        return 0;
    }

    /**
     * save build checked number
     *
     * @param job     Jenkins job
     * @param jobInfo remote Job info
     * @param number  trigger number
     */
    public static void saveCheckedNumber(BuildableItem job, RemoteJobInfo jobInfo, int number) throws IOException {
        SavedJobInfo savedJobInfo = getSavedJobInfo(job, jobInfo);
        if (savedJobInfo == null) {
            savedJobInfo = new SavedJobInfo();
        }

        savedJobInfo.setCheckedNumber(number);

        saveBuildInfo(job, jobInfo, savedJobInfo);
    }

    /**
     * save build result json
     *
     * @param job        Jenkins job
     * @param jobInfo    remote Job info
     * @param resultJson result json
     */
    public static void saveBuildResultJson(BuildableItem job, RemoteJobInfo jobInfo, SourceMap resultJson) throws IOException {
        SavedJobInfo savedJobInfo = getSavedJobInfo(job, jobInfo);
        if (savedJobInfo == null) {
            savedJobInfo = new SavedJobInfo();
        }

        savedJobInfo.setResultJson(resultJson.getSource());

        saveBuildInfo(job, jobInfo, savedJobInfo);
    }

    /**
     * save build info
     *
     * @param job          Jenkins job
     * @param jobInfo      remote Job info
     * @param remoteResult api result
     */
    public static void saveBuildInfo(BuildableItem job, RemoteJobInfo jobInfo, SourceMap remoteResult) throws IOException {
        SavedJobInfo savedJobInfo = getSavedJobInfo(job, jobInfo);
        if (savedJobInfo == null) {
            savedJobInfo = new SavedJobInfo();
        }

        savedJobInfo.setResult(remoteResult.getSource());

        saveBuildInfo(job, jobInfo, savedJobInfo);
    }

    /**
     * clean
     *
     * @param job            Jenkins job
     * @param remoteJobInfos remote Job infos
     */
    @NonNull
    public static List<SavedJobInfo> cleanUnusedBuildInfo(BuildableItem job, List<RemoteJobInfo> remoteJobInfos) throws IOException {
        List<SavedJobInfo> removed = new ArrayList<>();
        if (remoteJobInfos != null) {
            List<SavedJobInfo> savedJobInfos = getSavedJobInfos(job);
            savedJobInfos.removeIf(savedJobInfo -> {
                boolean match = remoteJobInfos.stream().noneMatch(
                        remoteJobInfo -> remoteJobInfo.getId().equals(savedJobInfo.getRemoteJob())
                );
                if (match) {
                    removed.add(savedJobInfo);
                }
                return match;
            });
            // save to file
            File file = getRemoteResultConfigFile(job);
            if (!file.getParentFile().exists()) {
                FileUtils.forceMkdirParent(file);
            }
            String string = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(savedJobInfos);
            FileUtils.writeStringToFile(file, string, StandardCharsets.UTF_8);
        }
        return removed;
    }

    /**
     * save build info to file
     *
     * @param job          Jenkins job
     * @param jobInfo      remote Job info
     * @param savedJobInfo save info
     */
    private static void saveBuildInfo(BuildableItem job, RemoteJobInfo jobInfo, SavedJobInfo savedJobInfo) throws IOException {
        // remote job info
        savedJobInfo.setRemoteServer(jobInfo.getRemoteServer());
        savedJobInfo.setRemoteJob(jobInfo.getId());
        savedJobInfo.setRemoteJobUrl(jobInfo.getRemoteJobUrl());
        savedJobInfo.setUid(jobInfo.getUid());

        // get saved list
        List<SavedJobInfo> savedJobInfos = getSavedJobInfos(job);
        // remove old
        savedJobInfos.removeIf(
                info -> info.getRemoteServer().equals(savedJobInfo.getRemoteServer())
                        && info.getRemoteJob().equals(savedJobInfo.getRemoteJob())
        );
        savedJobInfos.add(savedJobInfo);
        // save to file
        File file = getRemoteResultConfigFile(job);
        if (!file.getParentFile().exists()) {
            FileUtils.forceMkdirParent(file);
        }
        String string = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(savedJobInfos);
        FileUtils.writeStringToFile(file, string, StandardCharsets.UTF_8);
    }

    /**
     * get remote result envs
     *
     * @param job Jenkins job
     * @return envs
     */
    public static Map<String, String> getJobRemoteResultEnvs(Item job) throws IOException {
        Map<String, String> envs = new HashMap<>();
        List<SavedJobInfo> savedJobInfos = getSavedJobInfos(job);
        for (int i = 0; i < savedJobInfos.size(); i++) {
            SavedJobInfo savedJobInfo = savedJobInfos.get(i);
            // only one
            if (i == 0) {
                envs.putAll(generateEnvs("REMOTE_", savedJobInfo));
            }
            // prefix with job id
            String prefix = "REMOTE_" +
                    (StringUtils.isNotEmpty(savedJobInfo.getUid()) ? savedJobInfo.getUid() : savedJobInfo.getRemoteJobName()) +
                    "_";
            envs.putAll(generateEnvs(prefix, savedJobInfo));
        }
        // jobs list
        List<String> jobs = savedJobInfos.stream()
                .map(info -> StringUtils.isNotBlank(info.getUid()) ? info.getUid() : info.getRemoteJobUrl())
                .collect(Collectors.toUnmodifiableList());
        envs.put("REMOTE_JOBS", new ObjectMapper().writeValueAsString(jobs));
        return envs;
    }

    /**
     * do api request
     *
     * @param job     Jenkins job
     * @param jobInfo remote Job info
     * @param apiUrl  api url
     * @return api result
     */
    private static SourceMap requestRemoteApi(Item job, RemoteJobInfo jobInfo, String apiUrl)
            throws IOException, UnSuccessfulRequestStatusException {
        // OkHttp Client
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();

        RemoteJenkinsServer remoteServer = RemoteJenkinsServerUtils
                .getRemoteJenkinsServer(jobInfo.getRemoteServer());

        // remote server configuration deleted
        if (remoteServer == null) {
            return null;
        }

        // trustAllCertificates
        if (remoteServer.isTrustAllCertificates()) {
            clientBuilder = clientBuilder
                    .sslSocketFactory(SSLSocketManager.getSSLSocketFactory(),
                            (X509TrustManager) SSLSocketManager.getTrustManager()[0])
                    .hostnameVerifier(SSLSocketManager.getHostnameVerifier());
        }
        OkHttpClient okHttpClient = clientBuilder.build();

        // OkHttp Request
        Request.Builder requestBuilder = new Request.Builder();
        // auth
        if (remoteServer.getAuth2() != null && remoteServer.getAuth2().getCredentials(job) != null) {
            requestBuilder = requestBuilder.header("Authorization", remoteServer.getAuth2().getCredentials(job));
        }

        // api url
        Request request = requestBuilder.url(apiUrl).get().build();

        Call call = okHttpClient.newCall(request);
        try (Response response = call.execute()) {
            if (response.isSuccessful()) {
                ResponseBody responseBody = response.body();
                if (null != responseBody) {
                    String body = responseBody.string();
                    // json
                    ObjectMapper mapper = new ObjectMapper();
                    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    return SourceMap.of(mapper.readValue(body, Map.class));
                }
            } else {
                throw new UnSuccessfulRequestStatusException("Response UnSuccess Code:" + response.code() + ",Url:" + apiUrl, response.code(), apiUrl);
            }
        }
        return null;
    }

    /**
     * get saved job info
     *
     * @param job     Jenkins job
     * @param jobInfo remote Job info
     * @return saved job info
     */
    private static SavedJobInfo getSavedJobInfo(Item job, RemoteJobInfo jobInfo) throws IOException {
        List<SavedJobInfo> savedJobInfos = getSavedJobInfos(job);
        return savedJobInfos.stream().filter(
                savedJobInfo -> savedJobInfo.getRemoteServer().equals(jobInfo.getRemoteServer())
                        && savedJobInfo.getRemoteJob().equals(jobInfo.getId())
        ).findAny().orElse(null);
    }

    /**
     * get saved job infos
     *
     * @param job Jenkins job
     * @return saved job infos
     */
    public static List<SavedJobInfo> getSavedJobInfos(Item job) throws IOException {
        File file = getRemoteResultConfigFile(job);
        if (file.exists()) {
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            CollectionType collectionType = TypeFactory.defaultInstance().constructCollectionType(List.class, SavedJobInfo.class);
            return mapper.readValue(file, collectionType);
        }
        return new ArrayList<>();
    }

    /**
     * get remote result config file
     *
     * @param job Jenkins job
     * @return config file
     */
    private static File getRemoteResultConfigFile(Item job) {
        return new File(job.getRootDir().getAbsolutePath() + "/remote-build-result.json");
    }

    /**
     * Generate envs
     *
     * @param prefix       prefix
     * @param savedJobInfo saved info
     * @return envs
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, String> generateEnvs(String prefix, SavedJobInfo savedJobInfo) {
        Map<String, String> envs = new HashMap<>();
        if (savedJobInfo.getResult() != null) {
            SourceMap sourceMap = SourceMap.of(savedJobInfo.getResult());
            // BUILD_NUMBER
            envs.put(prefix + "BUILD_NUMBER", sourceMap.stringValue("number"));
            // TIMESTAMP
            envs.put(prefix + "BUILD_TIMESTAMP", sourceMap.stringValue("timestamp"));
            // BUILD_URL
            envs.put(prefix + "BUILD_URL", sourceMap.stringValue("url"));
            // BUILD_RESULT
            envs.put(prefix + "BUILD_RESULT", sourceMap.stringValue("result"));

            // Parameters
            List<Map> actions = sourceMap.listValue("actions", Map.class);
            if (actions != null) {
                for (Map action : actions) {
                    SourceMap actionMap = SourceMap.of(action);
                    if (actionMap.stringValue("_class") != null
                            && "hudson.model.ParametersAction".equals(actionMap.stringValue("_class"))) {
                        List<Map> parameters = actionMap.listValue("parameters", Map.class);
                        if (parameters != null) {
                            for (Map parameter : parameters) {
                                SourceMap parameterMap = SourceMap.of(parameter);
                                if (parameterMap.stringValue("name") != null) {
                                    String key = prefix + "PARAMETER_" + parameterMap.stringValue("name");
                                    envs.put(key, parameterMap.stringValue("value"));
                                }
                            }
                        }
                    }
                }
            }

            // result json
            Map<String, Object> resultJson = savedJobInfo.getResultJson();
            if (resultJson != null) {
                SourceMap map = SourceMap.of(resultJson);
                for (String key : resultJson.keySet()) {
                    envs.put(prefix + "RESULT_" + key, map.stringValue(key));
                }
            }
        }
        return envs;
    }

}
