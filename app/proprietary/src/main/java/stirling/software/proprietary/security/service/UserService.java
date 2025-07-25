package stirling.software.proprietary.security.service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.model.enumeration.Role;
import stirling.software.common.model.exception.UnsupportedProviderException;
import stirling.software.common.service.UserServiceInterface;
import stirling.software.proprietary.model.Team;
import stirling.software.proprietary.security.database.repository.AuthorityRepository;
import stirling.software.proprietary.security.database.repository.UserRepository;
import stirling.software.proprietary.security.model.AuthenticationType;
import stirling.software.proprietary.security.model.Authority;
import stirling.software.proprietary.security.model.User;
import stirling.software.proprietary.security.repository.TeamRepository;
import stirling.software.proprietary.security.saml2.CustomSaml2AuthenticatedPrincipal;
import stirling.software.proprietary.security.session.SessionPersistentRegistry;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService implements UserServiceInterface {

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final AuthorityRepository authorityRepository;

    private final PasswordEncoder passwordEncoder;

    private final MessageSource messageSource;

    private final SessionPersistentRegistry sessionRegistry;

    private final DatabaseServiceInterface databaseService;

    private final ApplicationProperties.Security.OAUTH2 oAuth2;

    @Transactional
    public void migrateOauth2ToSSO() {
        userRepository
                .findByAuthenticationTypeIgnoreCase("OAUTH2")
                .forEach(
                        user -> {
                            user.setAuthenticationType(AuthenticationType.SSO);
                            userRepository.save(user);
                        });
    }

    // Handle OAUTH2 login and user auto creation.
    public void processSSOPostLogin(String username, boolean autoCreateUser)
            throws IllegalArgumentException, SQLException, UnsupportedProviderException {
        if (!isUsernameValid(username)) {
            return;
        }
        Optional<User> existingUser = findByUsernameIgnoreCase(username);
        if (existingUser.isPresent()) {
            return;
        }
        if (autoCreateUser) {
            saveUser(username, AuthenticationType.SSO);
        }
    }

    public Authentication getAuthentication(String apiKey) {
        Optional<User> user = getUserByApiKey(apiKey);
        if (user.isEmpty()) {
            throw new UsernameNotFoundException("API key is not valid");
        }
        // Convert the user into an Authentication object
        return new UsernamePasswordAuthenticationToken( // principal (typically the user)
                user, // credentials (we don't expose the password or API key here)
                null, // user's authorities (roles/permissions)
                getAuthorities(user.get()));
    }

    private Collection<? extends GrantedAuthority> getAuthorities(User user) {
        // Convert each Authority object into a SimpleGrantedAuthority object.
        return user.getAuthorities().stream()
                .map((Authority authority) -> new SimpleGrantedAuthority(authority.getAuthority()))
                .toList();
    }

    private String generateApiKey() {
        String apiKey;
        do {
            apiKey = UUID.randomUUID().toString();
        } while ( // Ensure uniqueness
        userRepository.findByApiKey(apiKey).isPresent());
        return apiKey;
    }

    public User addApiKeyToUser(String username) {
        Optional<User> userOpt = findByUsernameIgnoreCase(username);
        User user = saveUser(userOpt, generateApiKey());
        try {
            databaseService.exportDatabase();
        } catch (SQLException | UnsupportedProviderException e) {
            log.error("Error exporting database after adding API key to user", e);
        }
        return user;
    }

    public User refreshApiKeyForUser(String username) {
        // reuse the add API key method for refreshing
        return addApiKeyToUser(username);
    }

    public String getApiKeyForUser(String username) {
        User user =
                findByUsernameIgnoreCase(username)
                        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        if (user.getApiKey() == null || user.getApiKey().length() == 0) {
            user = addApiKeyToUser(username);
        }
        return user.getApiKey();
    }

    public boolean isValidApiKey(String apiKey) {
        return userRepository.findByApiKey(apiKey).isPresent();
    }

    public Optional<User> getUserByApiKey(String apiKey) {
        return userRepository.findByApiKey(apiKey);
    }

    public Optional<User> loadUserByApiKey(String apiKey) {
        Optional<User> user = userRepository.findByApiKey(apiKey);
        if (user.isPresent()) {
            return user;
        }
        // or throw an exception
        return null;
    }

    public boolean validateApiKeyForUser(String username, String apiKey) {
        Optional<User> userOpt = findByUsernameIgnoreCase(username);
        return userOpt.isPresent() && apiKey.equals(userOpt.get().getApiKey());
    }

    public void saveUser(String username, AuthenticationType authenticationType)
            throws IllegalArgumentException, SQLException, UnsupportedProviderException {
        saveUser(username, authenticationType, (Long) null, Role.USER.getRoleId());
    }

    private User saveUser(Optional<User> user, String apiKey) {
        if (user.isPresent()) {
            user.get().setApiKey(apiKey);
            return userRepository.save(user.get());
        }
        throw new UsernameNotFoundException("User not found");
    }

    public User saveUser(
            String username, AuthenticationType authenticationType, Long teamId, String role)
            throws IllegalArgumentException, SQLException, UnsupportedProviderException {
        return saveUserCore(
                username, // username
                null, // password
                authenticationType, // authenticationType
                teamId, // teamId
                null, // team
                role, // role
                false, // firstLogin
                true // enabled
                );
    }

    public User saveUser(
            String username, AuthenticationType authenticationType, Team team, String role)
            throws IllegalArgumentException, SQLException, UnsupportedProviderException {
        return saveUserCore(
                username, // username
                null, // password
                authenticationType, // authenticationType
                null, // teamId
                team, // team
                role, // role
                false, // firstLogin
                true // enabled
                );
    }

    public User saveUser(String username, String password, Long teamId)
            throws IllegalArgumentException, SQLException, UnsupportedProviderException {
        return saveUserCore(
                username, // username
                password, // password
                AuthenticationType.WEB, // authenticationType
                teamId, // teamId
                null, // team
                Role.USER.getRoleId(), // role
                false, // firstLogin
                true // enabled
                );
    }

    public User saveUser(
            String username, String password, Team team, String role, boolean firstLogin)
            throws IllegalArgumentException, SQLException, UnsupportedProviderException {
        return saveUserCore(
                username, // username
                password, // password
                AuthenticationType.WEB, // authenticationType
                null, // teamId
                team, // team
                role, // role
                firstLogin, // firstLogin
                true // enabled
                );
    }

    public User saveUser(
            String username, String password, Long teamId, String role, boolean firstLogin)
            throws IllegalArgumentException, SQLException, UnsupportedProviderException {
        return saveUserCore(
                username, // username
                password, // password
                AuthenticationType.WEB, // authenticationType
                teamId, // teamId
                null, // team
                role, // role
                firstLogin, // firstLogin
                true // enabled
                );
    }

    public void saveUser(String username, String password, Long teamId, String role)
            throws IllegalArgumentException, SQLException, UnsupportedProviderException {
        saveUser(username, password, teamId, role, false);
    }

    public void saveUser(
            String username, String password, Long teamId, boolean firstLogin, boolean enabled)
            throws IllegalArgumentException, SQLException, UnsupportedProviderException {
        saveUserCore(
                username, // username
                password, // password
                AuthenticationType.WEB, // authenticationType
                teamId, // teamId
                null, // team
                Role.USER.getRoleId(), // role
                firstLogin, // firstLogin
                enabled // enabled
                );
    }

    public void deleteUser(String username) {
        Optional<User> userOpt = findByUsernameIgnoreCase(username);
        if (userOpt.isPresent()) {
            for (Authority authority : userOpt.get().getAuthorities()) {
                if (authority.getAuthority().equals(Role.INTERNAL_API_USER.getRoleId())) {
                    return;
                }
            }
            userRepository.delete(userOpt.get());
        }
        invalidateUserSessions(username);
    }

    public boolean usernameExists(String username) {
        return findByUsername(username).isPresent();
    }

    public boolean usernameExistsIgnoreCase(String username) {
        return findByUsernameIgnoreCase(username).isPresent();
    }

    public boolean hasUsers() {
        long userCount = userRepository.count();
        if (findByUsernameIgnoreCase(Role.INTERNAL_API_USER.getRoleId()).isPresent()) {
            userCount -= 1;
        }
        return userCount > 0;
    }

    public void updateUserSettings(String username, Map<String, String> updates)
            throws SQLException, UnsupportedProviderException {
        Optional<User> userOpt = findByUsernameIgnoreCaseWithSettings(username);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            Map<String, String> settingsMap = user.getSettings();
            if (settingsMap == null) {
                settingsMap = new HashMap<>();
            }
            settingsMap.clear();
            settingsMap.putAll(updates);
            user.setSettings(settingsMap);
            userRepository.save(user);
            databaseService.exportDatabase();
        }
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public Optional<User> findByUsernameIgnoreCase(String username) {
        return userRepository.findByUsernameIgnoreCase(username);
    }

    public Optional<User> findByUsernameIgnoreCaseWithSettings(String username) {
        return userRepository.findByUsernameIgnoreCaseWithSettings(username);
    }

    public Authority findRole(User user) {
        return authorityRepository.findByUserId(user.getId());
    }

    public void changeUsername(User user, String newUsername)
            throws IllegalArgumentException, SQLException, UnsupportedProviderException {
        if (!isUsernameValid(newUsername)) {
            throw new IllegalArgumentException(getInvalidUsernameMessage());
        }
        user.setUsername(newUsername);
        userRepository.save(user);
        databaseService.exportDatabase();
    }

    public void changePassword(User user, String newPassword)
            throws SQLException, UnsupportedProviderException {
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        databaseService.exportDatabase();
    }

    public void changeFirstUse(User user, boolean firstUse)
            throws SQLException, UnsupportedProviderException {
        user.setFirstLogin(firstUse);
        userRepository.save(user);
        databaseService.exportDatabase();
    }

    public void changeRole(User user, String newRole)
            throws SQLException, UnsupportedProviderException {
        Authority userAuthority = this.findRole(user);
        userAuthority.setAuthority(newRole);
        authorityRepository.save(userAuthority);
        databaseService.exportDatabase();
    }

    public void changeUserEnabled(User user, Boolean enbeled)
            throws SQLException, UnsupportedProviderException {
        user.setEnabled(enbeled);
        userRepository.save(user);
        databaseService.exportDatabase();
    }

    public void changeUserTeam(User user, Team team)
            throws SQLException, UnsupportedProviderException {
        if (team == null) {
            team = getDefaultTeam();
        }
        user.setTeam(team);
        userRepository.save(user);
        databaseService.exportDatabase();
    }

    public boolean isPasswordCorrect(User user, String currentPassword) {
        return passwordEncoder.matches(currentPassword, user.getPassword());
    }

    /**
     * Resolves a team based on the provided information, with consistent error handling.
     *
     * @param teamId The ID of the team to find, may be null
     * @param defaultTeamSupplier A supplier that provides a default team when teamId is null
     * @return The resolved Team object
     * @throws IllegalArgumentException If the teamId is invalid
     */
    private Team resolveTeam(Long teamId, Supplier<Team> defaultTeamSupplier) {
        if (teamId == null) {
            return defaultTeamSupplier.get();
        }

        return teamRepository
                .findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid team ID: " + teamId));
    }

    /**
     * Gets the default team, creating it if it doesn't exist.
     *
     * @return The default team
     */
    private Team getDefaultTeam() {
        return teamRepository
                .findByName("Default")
                .orElseGet(
                        () -> {
                            Team team = new Team();
                            team.setName("Default");
                            return teamRepository.save(team);
                        });
    }

    /**
     * Core implementation for saving a user with all possible parameters. This method centralizes
     * the common logic for all saveUser variants.
     *
     * @param username Username for the new user
     * @param password Password for the user (may be null for SSO/OAuth users)
     * @param authenticationType Type of authentication (WEB, SSO, etc.)
     * @param teamId ID of the team to assign (may be null to use default)
     * @param team Team object to assign (takes precedence over teamId if both provided)
     * @param role Role to assign to the user
     * @param firstLogin Whether this is the user's first login
     * @param enabled Whether the user account is enabled
     * @return The saved User object
     * @throws IllegalArgumentException If username is invalid or team is invalid
     * @throws SQLException If database operation fails
     * @throws UnsupportedProviderException If provider is not supported
     */
    private User saveUserCore(
            String username,
            String password,
            AuthenticationType authenticationType,
            Long teamId,
            Team team,
            String role,
            boolean firstLogin,
            boolean enabled)
            throws IllegalArgumentException, SQLException, UnsupportedProviderException {

        if (!isUsernameValid(username)) {
            throw new IllegalArgumentException(getInvalidUsernameMessage());
        }

        User user = new User();
        user.setUsername(username);

        // Set password if provided
        if (password != null && !password.isEmpty()) {
            user.setPassword(passwordEncoder.encode(password));
        }

        // Set authentication type
        user.setAuthenticationType(authenticationType);

        // Set enabled status
        user.setEnabled(enabled);

        // Set first login flag
        user.setFirstLogin(firstLogin);

        // Set role (authority)
        if (role == null) {
            role = Role.USER.getRoleId();
        }
        user.addAuthority(new Authority(role, user));

        // Resolve and set team
        if (team != null) {
            user.setTeam(team);
        } else {
            user.setTeam(resolveTeam(teamId, this::getDefaultTeam));
        }

        // Save user
        userRepository.save(user);

        // Export database
        databaseService.exportDatabase();

        return user;
    }

    public boolean isUsernameValid(String username) {
        // Checks whether the simple username is formatted correctly
        // Regular expression for user name: Min. 3 characters, max. 50 characters
        boolean isValidSimpleUsername =
                username.matches("^[a-zA-Z0-9](?!.*[-@._+]{2,})[a-zA-Z0-9@._+-]{1,48}[a-zA-Z0-9]$");

        // Checks whether the email address is formatted correctly
        // Regular expression for email addresses: Max. 320 characters, with RFC-like validation
        boolean isValidEmail =
                username.matches(
                        "^(?=.{1,320}$)(?=.{1,64}@)[A-Za-z0-9](?:[A-Za-z0-9_.+-]*[A-Za-z0-9])?@[^-][A-Za-z0-9-]+(?:\\\\.[A-Za-z0-9-]+)*(?:\\\\.[A-Za-z]{2,})$");

        List<String> notAllowedUserList = new ArrayList<>();
        notAllowedUserList.add("ALL_USERS".toLowerCase());
        notAllowedUserList.add("anonymoususer");
        boolean notAllowedUser = notAllowedUserList.contains(username.toLowerCase());
        return (isValidSimpleUsername || isValidEmail) && !notAllowedUser;
    }

    private String getInvalidUsernameMessage() {
        return messageSource.getMessage(
                "invalidUsernameMessage", null, LocaleContextHolder.getLocale());
    }

    public boolean hasPassword(String username) {
        Optional<User> user = findByUsernameIgnoreCase(username);
        return user.isPresent() && user.get().hasPassword();
    }

    public boolean isAuthenticationTypeByUsername(
            String username, AuthenticationType authenticationType) {
        Optional<User> user = findByUsernameIgnoreCase(username);
        return user.isPresent()
                && authenticationType.name().equalsIgnoreCase(user.get().getAuthenticationType());
    }

    public boolean isUserDisabled(String username) {
        Optional<User> userOpt = findByUsernameIgnoreCase(username);
        return userOpt.map(user -> !user.isEnabled()).orElse(false);
    }

    public void invalidateUserSessions(String username) {
        String usernameP = "";

        for (Object principal : sessionRegistry.getAllPrincipals()) {
            for (SessionInformation sessionsInformation :
                    sessionRegistry.getAllSessions(principal, false)) {
                if (principal instanceof UserDetails detailsUser) {
                    usernameP = detailsUser.getUsername();
                } else if (principal instanceof OAuth2User oAuth2User) {
                    usernameP = oAuth2User.getName();
                } else if (principal instanceof CustomSaml2AuthenticatedPrincipal saml2User) {
                    usernameP = saml2User.name();
                } else if (principal instanceof String stringUser) {
                    usernameP = stringUser;
                }
                if (usernameP.equalsIgnoreCase(username)) {
                    sessionRegistry.expireSession(sessionsInformation.getSessionId());
                }
            }
        }
    }

    public String getCurrentUsername() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        if (principal instanceof UserDetails detailsUser) {
            return detailsUser.getUsername();
        } else if (principal instanceof User domainUser) {
            return domainUser.getUsername();
        } else if (principal instanceof OAuth2User oAuth2User) {
            return oAuth2User.getAttribute(oAuth2.getUseAsUsername());
        } else if (principal instanceof CustomSaml2AuthenticatedPrincipal saml2User) {
            return saml2User.name();
        } else if (principal instanceof String stringUser) {
            return stringUser;
        }
        return null;
    }

    @Transactional
    public void syncCustomApiUser(String customApiKey) {
        if (customApiKey == null || customApiKey.trim().isBlank()) {
            return;
        }

        String username = "CUSTOM_API_USER";
        Optional<User> existingUser = findByUsernameIgnoreCase(username);

        existingUser.ifPresentOrElse(
                user -> {
                    // Update API key if it has changed
                    User updatedUser = existingUser.get();

                    if (!customApiKey.equals(updatedUser.getApiKey())) {
                        updatedUser.setApiKey(customApiKey);
                        userRepository.save(updatedUser);
                    }
                },
                () -> {
                    // Create new user with API role
                    User user = new User();
                    user.setUsername(username);
                    user.setPassword(UUID.randomUUID().toString());
                    user.setEnabled(true);
                    user.setFirstLogin(false);
                    user.setAuthenticationType(AuthenticationType.WEB);
                    user.setApiKey(customApiKey);
                    user.addAuthority(new Authority(Role.INTERNAL_API_USER.getRoleId(), user));
                    userRepository.save(user);
                });

        try {
            databaseService.exportDatabase();
        } catch (SQLException | UnsupportedProviderException e) {
            log.error("Error exporting database after synchronising custom API user", e);
        }
    }

    public long getTotalUsersCount() {
        // Count all users in the database
        long userCount = userRepository.count();
        // Exclude the internal API user from the count
        if (findByUsernameIgnoreCase(Role.INTERNAL_API_USER.getRoleId()).isPresent()) {
            userCount -= 1;
        }
        return userCount;
    }

    public List<User> getUsersWithoutTeam() {
        return userRepository.findAllWithoutTeam();
    }

    public void saveAll(List<User> users) {
        userRepository.saveAll(users);
    }
}
