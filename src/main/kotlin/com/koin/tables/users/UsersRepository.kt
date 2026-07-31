package com.koin.tables.users

import org.mindrot.jbcrypt.BCrypt

object UsersRepository {
    fun transformPasswordInHash(password: String): String {
        return BCrypt.hashpw(password, BCrypt.gensalt())
    }
}