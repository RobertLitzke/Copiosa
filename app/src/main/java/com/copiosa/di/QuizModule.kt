package com.copiosa.di

import com.copiosa.domain.quiz.NumberSpeller
import com.copiosa.domain.quiz.WordNumberSpeller
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class QuizModule {

    @Binds
    abstract fun bindNumberSpeller(impl: WordNumberSpeller): NumberSpeller
}
