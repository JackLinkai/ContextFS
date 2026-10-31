package com.contextfs.api.controller;

import com.contextfs.api.common.ApiResponse;
import com.contextfs.api.dto.NodeDto;
import com.contextfs.api.dto.ReadResultDto;
import com.contextfs.api.dto.TreeNodeDto;
import com.contextfs.api.dto.WriteRequest;
import com.contextfs.api.dto.WriteResultDto;
import com.contextfs.api.fs.FileSystemService;
import com.contextfs.core.model.Level;
import com.contextfs.core.model.NodeType;
import com.contextfs.core.uri.CtxfsUri;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 虚拟文件系统 REST 接口：/api/v1/fs/*。 */
@RestController
@RequestMapping("/api/v1/fs")
public class FsController {

    /** 调用方允许访问的 scope（如 user/10001），缺省不限制；跨 scope 访问返回 403。 */
    public static final String SCOPE_HEADER = "X-ContextFS-Scope";

    private final FileSystemService fileSystemService;

    public FsController(FileSystemService fileSystemService) {
        this.fileSystemService = fileSystemService;
    }

    @PostMapping("/write")
    public ApiResponse<WriteResultDto> write(
            @Valid @RequestBody WriteRequest request,
            @RequestHeader(value = SCOPE_HEADER, required = false) String allowedScope) {
        CtxfsUri uri = CtxfsUri.parse(request.uri());
        boolean generateLevels = request.generateLevels() == null || request.generateLevels();
        return ApiResponse.ok(
                fileSystemService.write(
                        uri,
                        request.content(),
                        request.type(),
                        request.metadata(),
                        generateLevels,
                        allowedScope));
    }

    @GetMapping("/read")
    public ApiResponse<ReadResultDto> read(
            @RequestParam String uri,
            @RequestParam(defaultValue = "L2") Level level,
            @RequestHeader(value = SCOPE_HEADER, required = false) String allowedScope) {
        return ApiResponse.ok(fileSystemService.read(CtxfsUri.parse(uri), level, allowedScope));
    }

    @GetMapping("/ls")
    public ApiResponse<List<NodeDto>> ls(
            @RequestParam String uri,
            @RequestHeader(value = SCOPE_HEADER, required = false) String allowedScope) {
        return ApiResponse.ok(fileSystemService.ls(CtxfsUri.parse(uri), allowedScope));
    }

    @GetMapping("/tree")
    public ApiResponse<TreeNodeDto> tree(
            @RequestParam String uri,
            @RequestParam(name = "depth", defaultValue = "8") int depth,
            @RequestHeader(value = SCOPE_HEADER, required = false) String allowedScope) {
        return ApiResponse.ok(fileSystemService.tree(CtxfsUri.parse(uri), depth, allowedScope));
    }

    @GetMapping("/find")
    public ApiResponse<List<NodeDto>> find(
            @RequestParam String uri,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) NodeType type,
            @RequestParam(name = "metadataKey", required = false) String metadataKey,
            @RequestParam(name = "metadataValue", required = false) String metadataValue,
            @RequestHeader(value = SCOPE_HEADER, required = false) String allowedScope) {
        return ApiResponse.ok(
                fileSystemService.find(
                        CtxfsUri.parse(uri),
                        keyword,
                        type,
                        metadataKey,
                        metadataValue,
                        allowedScope));
    }
}
