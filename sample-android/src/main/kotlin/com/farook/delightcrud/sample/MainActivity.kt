package com.farook.delightcrud.sample

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var repo: TodoRepository
    private lateinit var adapter: TodoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = (application as SampleApplication).todoRepo

        adapter = TodoAdapter(
            onToggle = { todo ->
                lifecycleScope.launch { repo.update(todo.copy(isCompleted = !todo.isCompleted)) }
            },
            onDelete = { todo ->
                lifecycleScope.launch { repo.delete(todo.id) }
            },
            onEditTitle = { todo, newTitle ->
                lifecycleScope.launch { repo.update(todo.copy(title = newTitle)) }
            }
        )

        findViewById<RecyclerView>(R.id.rvTodos).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        val etInput    = findViewById<EditText>(R.id.etTodoInput)
        val rgPriority = findViewById<RadioGroup>(R.id.rgPriority)
        val btnAdd     = findViewById<Button>(R.id.btnAdd)

        fun addTodo() {
            val title = etInput.text.toString().trim()
            if (title.isEmpty()) { Toast.makeText(this, "Title is empty", Toast.LENGTH_SHORT).show(); return }
            val urgency = if (rgPriority.checkedRadioButtonId == R.id.rbHigh) 1 else 0
            lifecycleScope.launch { repo.insert(Todo(title = title, urgency = urgency)) }
            etInput.setText("")
        }

        btnAdd.setOnClickListener { addTodo() }
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { addTodo(); true } else false
        }

        // Reactive list: observeAll() re-emits after every insert/update/delete —
        // no manual refresh calls anywhere.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeAll().collect { todos ->
                    adapter.submitList(
                        todos.sortedWith(compareByDescending<Todo> { it.urgency }.thenBy { it.id })
                    )
                }
            }
        }
    }
}
