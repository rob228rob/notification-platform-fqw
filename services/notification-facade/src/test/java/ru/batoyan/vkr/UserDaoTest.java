package ru.batoyan.vkr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import ru.batoyan.vkr.services.rest.user.UserRepository;
import ru.batoyan.vkr.services.rest.user.UserEntity;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.*;
import org.springframework.test.context.ActiveProfiles;

@JdbcTest
@ActiveProfiles("test")
public class UserDaoTest {

    @Autowired
    private UserRepository userDao;

    private UserEntity testUser;

    @BeforeEach
    public void setUp() {
        testUser = UserEntity.builder()
                .email("test@test.com")
                .id(UUID.randomUUID())
                .verified(true)
                .phoneNumber("+79002243232")
                .firstName("name")
                .lastName("surname")
                .build();
        userDao.save(testUser);
    }

    @Test
    public void testFindById() {
        var userOptional = userDao.findById(testUser.getId());

        Assertions.assertTrue(userOptional.isPresent());

        var user = userOptional.get();
        Assertions.assertEquals(testUser.getId(), user.getId());
        Assertions.assertEquals(testUser.getPassword(), user.getPassword());
        Assertions.assertEquals(testUser.getFirstName(), user.getFirstName());
        Assertions.assertEquals(testUser.getLastName(), user.getLastName());
        Assertions.assertEquals(testUser.getEmail(), user.getEmail());
    }

    @Test
    public void testFindUserByFakeIdThrowingException() {
        UUID fakeId = UUID.randomUUID();

        var user = userDao.findById(fakeId);
        Assertions.assertTrue(user.isEmpty());
    }

    @Test
    public void testFindByEmail() {
        Optional<UserEntity> foundUser = userDao.findByEmail(testUser.getEmail());
        assertTrue(foundUser.isPresent());
        assertEquals(testUser.getId(), foundUser.get().getId());
    }

    @Test
    public void testExistsByEmail() {
        boolean exists = userDao.existsByEmail(testUser.getEmail());
        assertTrue(exists);
    }

    @Test
    public void testDeleteByEmail() {
        userDao.deleteByEmail(testUser.getEmail());

        Optional<UserEntity> foundUser = userDao.findByEmail(testUser.getEmail());
        assertFalse(foundUser.isPresent());
    }

    @Test
    public void testDeleteById() {
        userDao.deleteById(testUser.getId());

        Optional<UserEntity> foundUser = userDao.findById(testUser.getId());
        assertFalse(foundUser.isPresent());
    }
}