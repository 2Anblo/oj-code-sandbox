package com.lingbo.ojcodesandbox;

import cn.hutool.core.io.resource.ResourceUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.lingbo.ojcodesandbox.model.ExecuteCodeRequest;
import com.lingbo.ojcodesandbox.model.ExecuteCodeResponse;
import com.lingbo.ojcodesandbox.model.ExecuteMessage;
import com.lingbo.ojcodesandbox.utils.DockerClientUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class JavaDockerCodeSandbox extends JavaCodeSandboxTemplate {


    private static final Long TIME_OUT = 10000L;

    private static Boolean FIRST_INIT = true;

    /**
     * 3.创建容器，上传编译文件，容器内执行程序
     * @param userCodeFile 用户代码文件
     * @param inputList 输入列表
     * @return
     */
    @Override
    public List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList) {
        String userCodeSaveParentPath = userCodeFile.getParentFile().getAbsolutePath();
        // 获取默认的 Docker Client
        DockerClient dockerClient = DockerClientUtils.getClient();

        // 拉取镜像
        String image = "openjdk:8-alpine";
        if (FIRST_INIT) {

            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback(){
                @Override
                public void onNext(PullResponseItem item) {
                    System.out.println("下载镜像：" + item.getStatus());
                    super.onNext(item);
                }
            };
            try {
                pullImageCmd.exec(pullImageResultCallback)
                        .awaitCompletion();
                FIRST_INIT = false;
            } catch (InterruptedException e) {
                System.out.println("拉取镜像异常");
                throw new RuntimeException(e);
            }
        }
        System.out.println("下载完成");

        // 创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        HostConfig hostConfig = new HostConfig();
        hostConfig.withMemory(100 * 1000 * 1000L);
        hostConfig.withMemorySwap(0L);
        hostConfig.withCpuCount(1L);
        hostConfig.setBinds(new Bind(userCodeSaveParentPath, new Volume("/app")));

        CreateContainerResponse createContainerResponse = containerCmd
                .withHostConfig(hostConfig)
                .withAttachStdout(true)
                .withNetworkDisabled(true)
                .withReadonlyRootfs(true)
                .withAttachStderr(true)
                .withAttachStdin(true)
                .withTty(false)
                .withCmd("sh", "-c", "tail -f /dev/null")
                .exec();
        System.out.println(createContainerResponse);
        String containerId = createContainerResponse.getId();

        // 启动容器
        dockerClient.startContainerCmd(containerId).exec();
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        try {
            for (String inputArgs : inputList) {
                ExecuteMessage executeMessage = runOnceInContainer(dockerClient, containerId, inputArgs);
                executeMessageList.add(executeMessage);
            }
        } finally {
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception ignored) {
            }
        }
        return executeMessageList;
    }

    private ExecuteMessage runOnceInContainer(DockerClient dockerClient, String containerId, String stdin) {
        ExecuteMessage executeMessage = new ExecuteMessage();
        StringBuilder messageBuilder = new StringBuilder();
        StringBuilder errorMessageBuilder = new StringBuilder();
        final long[] maxMemoryBytes = {0L};

        StopWatch stopWatch = new StopWatch();
        StatsCmd statsCmd = null;
        ResultCallback<Statistics> statisticsResultCallback = null;
        ExecStartResultCallback execStartResultCallback = null;

        String[] cmdArray = new String[]{"java", "-cp", "/app", "Main"};
        ExecCreateCmdResponse cmdResponse = dockerClient.execCreateCmd(containerId)
                .withCmd(cmdArray)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
        String execId = cmdResponse.getId();

        boolean timeout = false;
        boolean completed = false;
        try {
            statsCmd = dockerClient.statsCmd(containerId);
            statisticsResultCallback = statsCmd.exec(
                    new ResultCallback<Statistics>() {
                        @Override
                        public void onNext(Statistics statistics) {
                            if (statistics == null || statistics.getMemoryStats() == null || statistics.getMemoryStats().getUsage() == null) {
                                return;
                            }
                            maxMemoryBytes[0] = Math.max(statistics.getMemoryStats().getUsage(), maxMemoryBytes[0]);
                        }

                        @Override
                        public void close() throws IOException {
                        }

                        @Override
                        public void onStart(Closeable closeable) {
                        }

                        @Override
                        public void onError(Throwable throwable) {
                        }

                        @Override
                        public void onComplete() {
                        }
                    }
            );

            execStartResultCallback = new ExecStartResultCallback() {
                @Override
                public void onNext(Frame frame) {
                    StreamType streamType = frame.getStreamType();
                    if (StreamType.STDERR.equals(streamType)) {
                        errorMessageBuilder.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    } else {
                        messageBuilder.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                    super.onNext(frame);
                }
            };

            stopWatch.start();
            String stdinToUse = stdin == null ? "" : stdin;
            if (!stdinToUse.isEmpty() && !stdinToUse.endsWith("\n")) {
                stdinToUse = stdinToUse + "\n";
            }
            dockerClient.execStartCmd(execId)
                    .withStdIn(new ByteArrayInputStream(stdinToUse.getBytes(StandardCharsets.UTF_8)))
                    .exec(execStartResultCallback);

            long startTime = System.currentTimeMillis();
            while (true) {
                InspectExecResponse inspectExecResponse = dockerClient.inspectExecCmd(execId).exec();
                Boolean running = getRunningFlag(inspectExecResponse);
                if (running == null) {
                    Long exitCodeLong = null;
                    try {
                        exitCodeLong = inspectExecResponse == null ? null : inspectExecResponse.getExitCodeLong();
                    } catch (Throwable ignored) {
                    }
                    running = exitCodeLong == null;
                }
                if (!Boolean.TRUE.equals(running)) {
                    completed = true;
                    break;
                }
                if (System.currentTimeMillis() - startTime > TIME_OUT) {
                    timeout = true;
                    break;
                }
                Thread.sleep(30);
            }
            stopWatch.stop();
        } catch (InterruptedException e) {
            errorMessageBuilder.append(e.getMessage());
        } finally {
            if (statsCmd != null) {
                try {
                    statsCmd.close();
                } catch (Exception ignored) {
                }
            }
            if (statisticsResultCallback != null) {
                try {
                    statisticsResultCallback.close();
                } catch (Exception ignored) {
                }
            }
            if (execStartResultCallback != null) {
                try {
                    execStartResultCallback.awaitCompletion(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                try {
                    execStartResultCallback.close();
                } catch (Exception ignored) {
                }
            }
        }

        Integer exitCode = null;
        try {
            InspectExecResponse inspectExecResponse = dockerClient.inspectExecCmd(execId).exec();
            if (inspectExecResponse != null && inspectExecResponse.getExitCodeLong() != null) {
                exitCode = inspectExecResponse.getExitCodeLong().intValue();
            }
        } catch (Exception ignored) {
        }

        executeMessage.setExitCode(exitCode);
        executeMessage.setTime(stopWatch.getLastTaskTimeMillis());
        if (maxMemoryBytes[0] > 0) {
            executeMessage.setMemory(maxMemoryBytes[0] / 1024 / 1024);
        }
        executeMessage.setMessage(messageBuilder.toString().trim());
        if (timeout || !completed) {
            executeMessage.setErrorMessage("执行超时");
        } else {
            executeMessage.setErrorMessage(errorMessageBuilder.toString().trim());
        }
        return executeMessage;
    }

    private Boolean getRunningFlag(InspectExecResponse inspectExecResponse) {
        if (inspectExecResponse == null) {
            return null;
        }
        try {
            java.lang.reflect.Method method = inspectExecResponse.getClass().getMethod("getRunning");
            Object value = method.invoke(inspectExecResponse);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Exception ignored) {
        }
        try {
            java.lang.reflect.Method method = inspectExecResponse.getClass().getMethod("isRunning");
            Object value = method.invoke(inspectExecResponse);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Exception ignored) {
        }
        return null;
    }



}
