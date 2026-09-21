package com.example.bestscaffold.ui.base

import android.os.Bundle
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    protected lateinit var binding: VB

    @LayoutRes
    protected abstract fun layoutRes(): Int

    protected abstract fun inflateBinding(): VB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = inflateBinding()
        setContentView(binding.root)
        initView()
        observeState()
    }

    protected abstract fun initView()

    protected abstract fun observeState()
}
