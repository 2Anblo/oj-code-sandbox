package com.lingbo.ojcodesandbox.controller;

import com.lingbo.ojcodesandbox.CppDockerCodeSandbox;
import com.lingbo.ojcodesandbox.JavaDockerCodeSandbox;
import com.lingbo.ojcodesandbox.JavaNativeCodeSandbox;
import com.lingbo.ojcodesandbox.model.ExecuteCodeRequest;
import com.lingbo.ojcodesandbox.model.ExecuteCodeResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController("/")
public class MainController {

    // 定义鉴权请求头和密钥
    private static final String AUTH_REQUEST_HEADER = "auth";

    private static final String AUTH_REQUEST_SECRET = "secretKey";

    @Resource
    private JavaNativeCodeSandbox javaNativeCodeSandbox;

    @Resource
    private CppDockerCodeSandbox cppDockerCodeSandbox;
    @Autowired
    private JavaDockerCodeSandbox javaDockerCodeSandbox;

    /**
     * 执行代码
     *
     * @param executeCodeRequest 执行代码请求
     * @param httpServletRequest
     * @param httpServletResponse
     * @return 执行代码响应
     * @throws Exception
     */
    @PostMapping("/executeCode")
    ExecuteCodeResponse executeCode(@RequestBody ExecuteCodeRequest executeCodeRequest
                                     , HttpServletRequest httpServletRequest
                                     , HttpServletResponse httpServletResponse) throws Exception {
        String authHeader = httpServletRequest.getHeader(AUTH_REQUEST_HEADER);
        // 基本的认证
        if (!AUTH_REQUEST_SECRET.equals(authHeader)) {
            httpServletResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }

        if (executeCodeRequest == null) {
            throw new RuntimeException("请求参数为空！");
        }

        String language = executeCodeRequest.getLanguage();
        if ("java".equalsIgnoreCase(language)) {
            return javaDockerCodeSandbox.executeCode(executeCodeRequest);
        }
        if ("cpp".equalsIgnoreCase(language) || "c++".equalsIgnoreCase(language)) {
            return cppDockerCodeSandbox.executeCode(executeCodeRequest);
        }
        throw new RuntimeException("暂不支持语言：" + language);
    }


}
