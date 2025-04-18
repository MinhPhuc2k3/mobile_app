package org.example.management.management.application.service.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.example.management.management.application.model.user.request.LoginRequest;
import org.example.management.management.application.model.user.request.UserFilterRequest;
import org.example.management.management.application.model.user.request.UserRequest;
import org.example.management.management.application.model.user.response.UserResponse;
import org.example.management.management.application.service.images.ImageService;
import org.example.management.management.application.utils.NumberUtils;
import org.example.management.management.domain.profile.User;
import org.example.management.management.domain.profile.UserRepository;
import org.example.management.management.domain.profile.User_;
import org.example.management.management.domain.task.Image;
import org.example.management.management.infastructure.exception.ConstrainViolationException;
import org.example.management.management.infastructure.persistance.ImageRepository;
import org.example.management.management.infastructure.persistance.JpaUserRepositoryInterface;
import org.example.management.management.interfaces.rest.ImageController;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final UserMapper userMapper;
//    private final PasswordEncoder passwordEncoder;

    private final JpaUserRepositoryInterface repositoryInterface;

    private final ImageController imageController;

    private final ImageRepository imageRepository;

    private final ImageService imageService;

    @Override
    @Transactional
    public UserResponse createUser(UserRequest request) {
        if (request == null) {
            throw new ConstrainViolationException("request", "Request is null");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ConstrainViolationException(request.getEmail(), "Email has been existed!");
        }
        if (userRepository.existsByPhone(request.getPhone())) {
            throw new ConstrainViolationException(request.getPhone(), "Phone has bean existed!");
        }
        User user = userMapper.toUser(request);

        String name = Stream.of(request.getFirstName(), request.getLastName())
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining(" "));
        user.setUserName(name);
        user.setPassword(request.getPassword());
        user.setRole(User.Role.member);
        userRepository.save(user);
        return this.getByIds(List.of(user.getId())).get(0);
    }


    @Override
    public UserResponse getUserById(int id) {
        User user = userRepository.findById(id)
                .orElseThrow(RuntimeException::new);
        return this.getByIds(List.of(user.getId())).get(0);
    }

    @Override
    @Transactional
    public UserResponse updateUser(int id, UserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ConstrainViolationException("id", "User not exists"));
        userMapper.updateUser(user, request);

        user.updateAddress(request.getAddress());

        userRepository.save(user);
        return this.getByIds(List.of(user.getId())).get(0);
    }

    @Override
    public List<UserResponse> getByIds(List<Integer> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            return Collections.emptyList();
        }

        var users = this.repositoryInterface.findByIdIn(userIds);
        var imageIds = users.stream()
                .map(User::getAvatarId)
                .filter(NumberUtils::isPositive)
                .toList();
        var images = this.imageRepository.findByIdIn(imageIds).stream()
                .collect(Collectors.toMap(Image::getId, Function.identity()));

        return users.stream()
                .map(user -> this.userMapper.toUserResponse(user, user.getAvatarId() == null ? null : images.get(user.getAvatarId())))
                .toList();
    }

    @Override
    public Page<UserResponse> filter(UserFilterRequest request) {
        var pageable = PageRequest.of(request.getPageNumber() - 1, request.getPageSize(), Sort.by(User_.ID).descending());

        var pageUsers = getUserResponse(request, pageable);

        return new PageImpl<>(pageUsers.getKey(), pageable, pageUsers.getRight());
    }

    @Override
    public void upload(int userId, MultipartFile file) throws IOException {
        var user = this.repositoryInterface.findById(userId)
                .orElseThrow(() -> new ConstrainViolationException(
                        "user",
                        "user not found by id = " + userId
                ));
        var imageSaved = this.imageService.uploadImageWithFile(file);
        user.setAvatarId(imageSaved.getId());

        this.repositoryInterface.save(user);
    }

    @Override
    public UserResponse findById(int userId) {
        var user = this.repositoryInterface.findById(userId)
                .orElseThrow(() -> new ConstrainViolationException(
                        "user",
                        "user not found by id = " + userId
                ));
        return this.userMapper.toUserResponse(user);
    }

    @Override
    public UserResponse login(LoginRequest request) {
        var possiblyUser = this.repositoryInterface.findByEmailAndPassword(request.getEmail(), request.getPassword());
        if (possiblyUser.isEmpty()) {
            throw new ConstrainViolationException(
                    "user",
                    "Email hoặc mật khẩu không đúng"
            );
        }
        return this.userMapper.toUserResponse(possiblyUser.get());
    }

    private Pair<List<UserResponse>, Long> getUserResponse(UserFilterRequest request, PageRequest pageable) {
        var specification = buildUserSpecification(request);

        var page = this.repositoryInterface.findAll(specification, pageable);

        var users = page.getContent()
                .stream()
                .map(this.userMapper::toUserResponse)
                .toList();

        return Pair.of(users, page.getTotalElements());
    }

    private Specification<User> buildUserSpecification(UserFilterRequest request) {
        Specification<User> specification = Specification.where(null);

        if (CollectionUtils.isNotEmpty(request.getIds())) {
            specification = specification.and(UserSpecification.hasIdIn(request.getIds()));
        }

        return specification;
    }
}
