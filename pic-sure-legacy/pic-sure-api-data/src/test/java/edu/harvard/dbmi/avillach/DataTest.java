package edu.harvard.dbmi.avillach;

import edu.harvard.dbmi.avillach.data.entity.BaseEntity;
import edu.harvard.dbmi.avillach.data.entity.AuthUser;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Unit test for simple App.
 */
public class DataTest {

	@Test
	public void BaseEntityBasicFunctionsTest() {
		BaseEntity user = new AuthUser();
		user.setUuid(UUID.fromString("6ef9387a-4cde-4253-bd47-0bdc74ff76ab"));

		BaseEntity user2 = new AuthUser();
		user2.setUuid(UUID.fromString("6ef9387a-4cde-4253-bd47-0bdc74ff76ab"));

		assertEquals(user, user2);
	}
}
