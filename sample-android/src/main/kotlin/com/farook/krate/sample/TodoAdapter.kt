package com.farook.krate.sample

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView

class TodoAdapter(
    private val onToggle:    (Todo) -> Unit,
    private val onDelete:    (Todo) -> Unit,
    private val onEditTitle: (Todo, String) -> Unit
) : RecyclerView.Adapter<TodoAdapter.ViewHolder>() {

    private val items = mutableListOf<Todo>()

    fun submitList(list: List<Todo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbDone:      CheckBox   = view.findViewById(R.id.cbDone)
        val tvTitle:     TextView   = view.findViewById(R.id.tvTitle)
        val tvPriority:  TextView   = view.findViewById(R.id.tvPriority)
        val btnDelete:   ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_todo, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val todo = items[position]

        holder.cbDone.isChecked = todo.isCompleted
        holder.tvTitle.text = todo.title

        // Strike-through when done
        holder.tvTitle.paintFlags = if (todo.isCompleted)
            holder.tvTitle.paintFlags or  Paint.STRIKE_THRU_TEXT_FLAG
        else
            holder.tvTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

        holder.tvPriority.visibility = if (todo.priority == 1) View.VISIBLE else View.GONE

        holder.cbDone.setOnClickListener { onToggle(todo) }
        holder.btnDelete.setOnClickListener { onDelete(todo) }

        // Long-press to edit title (UPDATE demo)
        holder.tvTitle.setOnLongClickListener {
            val ctx = holder.itemView.context
            val input = android.widget.EditText(ctx).apply {
                setText(todo.title)
                selectAll()
            }
            AlertDialog.Builder(ctx)
                .setTitle("Edit todo")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val newTitle = input.text.toString().trim()
                    if (newTitle.isNotEmpty()) onEditTitle(todo, newTitle)
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }
}
