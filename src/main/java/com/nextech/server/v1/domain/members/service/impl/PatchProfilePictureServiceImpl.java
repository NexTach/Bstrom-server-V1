package com.nextech.server.v1.domain.members.service.impl;

import com.nextech.server.v1.domain.members.dto.response.PatchProfilePictureResponse;
import com.nextech.server.v1.domain.members.service.PatchProfilePictureService;
import com.nextech.server.v1.global.aws.service.FileDeleteService;
import com.nextech.server.v1.global.aws.service.FileUploadService;
import com.nextech.server.v1.global.exception.FileUploadFailedException;
import com.nextech.server.v1.global.exception.InvalidFileExtensionException;
import com.nextech.server.v1.global.exception.MaxFileSizeExceededException;
import com.nextech.server.v1.global.exception.MissingFileNameException;
import com.nextech.server.v1.global.members.entity.Members;
import com.nextech.server.v1.global.members.repository.MemberRepository;
import com.nextech.server.v1.global.members.service.MemberAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import kotlin.Pair;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class PatchProfilePictureServiceImpl implements PatchProfilePictureService {

    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final List<String> ALLOWED_EXTENSIONS = List.of("jpg", "jpeg", "png", "svg");
    private final MemberAuthService memberAuthService;
    private final FileUploadService fileUploadService;
    private final FileDeleteService fileDeleteService;
    private final MemberRepository memberRepository;
    @Value("${AWS_S3_BUCKET}")
    private String BUCKET_NAME;
    @Value("${CLOUDFLARE_BUCKET_SUBDOMAIN}")
    private String CLUDFLARE_BUCKET_SUBDOMAIN;

    @Override
    @Transactional
    public ResponseEntity<PatchProfilePictureResponse> patchProfilePicture(HttpServletRequest request, MultipartFile file) {
        Members member = memberAuthService.getMemberByToken(request);
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new MissingFileNameException("Missing file name");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new MaxFileSizeExceededException("File size exceeds the maximum allowed size of 10MB");
        }
        String fileExtension = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(fileExtension)) {
            throw new InvalidFileExtensionException("Invalid file extension");
        }
        CompletableFuture<Void> deleteFuture = null;
        if (member.getProfilePictureURI() != null) {
            deleteFuture = fileDeleteService.deleteFile(member.getProfilePictureName(), BUCKET_NAME);
        }
        try {
            CompletableFuture<Pair<String, String>> uploadFuture = fileUploadService.uploadFile(file, BUCKET_NAME);
            if (deleteFuture != null) {
                deleteFuture.join();
            }
            Pair<String, String> uploadResult = uploadFuture.join();
            member.setProfilePictureURI(uploadResult.getFirst());
            member.setProfilePictureName(uploadResult.getSecond());
            memberRepository.save(member);
        } catch (IOException e) {
            throw new FileUploadFailedException("Failed to upload file");
        }
        PatchProfilePictureResponse response = new PatchProfilePictureResponse(
                CLUDFLARE_BUCKET_SUBDOMAIN + "/" + member.getProfilePictureName()
        );
        return ResponseEntity.ok(response);
    }
}