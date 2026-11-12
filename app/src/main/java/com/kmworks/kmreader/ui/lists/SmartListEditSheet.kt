package com.kmworks.kmreader.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kmworks.kmreader.data.SmartListTarget

/** 单条匹配规则：字段 + 运算符 + 值（对齐 SmartListEditSheet 条件构建器的一行）。 */
data class SmartListRule(
    val field: String,
    val operator: String,
    val value: String,
)

/** 规则可选字段（对齐原作条件的字段选择器）。 */
val SmartListRuleFields = listOf("阅读状态", "标签", "作者", "发布年份", "页数")

/** 规则可选运算符（对齐原作条件的运算符选择器）。 */
val SmartListRuleOperators = listOf("等于", "不等于", "包含", "大于", "小于")

/** 智能列表规则编辑器（对齐 SmartListEditSheet.swift：名称/目标类型/条件组构建器/保存）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartListEditSheet(
    onSave: (name: String, target: SmartListTarget, matchAll: Boolean, rules: List<SmartListRule>) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    initialTarget: SmartListTarget = SmartListTarget.Book,
    initialMatchAll: Boolean = true,
    initialRules: List<SmartListRule> = emptyList(),
    isEdit: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var target by remember { mutableStateOf(initialTarget) }
    var matchAll by remember { mutableStateOf(initialMatchAll) }
    val rules = remember {
        mutableStateListOf<SmartListRule>().also {
            it.addAll(initialRules.ifEmpty { listOf(SmartListRule("阅读状态", "等于", "未读")) })
        }
    }
    val canSave = name.isNotBlank() && rules.isNotEmpty() && rules.all { it.value.isNotBlank() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // 头部：标题 + 关闭
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isEdit) "编辑智能列表" else "新建智能列表",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            SheetLabel("目标类型")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmartListTarget.entries.forEach { option ->
                    FilterChip(
                        selected = target == option,
                        onClick = { target = option },
                        label = { Text(option.label) },
                    )
                }
            }

            SheetLabel("条件")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = matchAll,
                    onClick = { matchAll = true },
                    label = { Text("满足全部条件") },
                )
                FilterChip(
                    selected = !matchAll,
                    onClick = { matchAll = false },
                    label = { Text("满足任一条件") },
                )
            }

            rules.forEachIndexed { index, rule ->
                RuleRow(
                    rule = rule,
                    onRuleChange = { rules[index] = it },
                    onRemove = { rules.removeAt(index) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            TextButton(
                onClick = { rules.add(SmartListRule("标签", "包含", "")) },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加条件")
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isEdit && onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                OutlinedButton(onClick = onDismiss) { Text("取消") }
                Button(
                    onClick = { onSave(name.trim(), target, matchAll, rules.toList()) },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存")
                }
            }
        }
    }
}

/** 条件行：字段下拉 + 运算符下拉 + 值输入 + 行尾删除。 */
@Composable
private fun RuleRow(
    rule: SmartListRule,
    onRuleChange: (SmartListRule) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RuleDropdown(
            value = rule.field,
            options = SmartListRuleFields,
            onSelect = { onRuleChange(rule.copy(field = it)) },
            modifier = Modifier.width(112.dp),
        )
        RuleDropdown(
            value = rule.operator,
            options = SmartListRuleOperators,
            onSelect = { onRuleChange(rule.copy(operator = it)) },
            modifier = Modifier.width(104.dp),
        )
        OutlinedTextField(
            value = rule.value,
            onValueChange = { onRuleChange(rule.copy(value = it)) },
            placeholder = { Text("值") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "删除条件",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 下拉选择按钮（字段/运算符共用）。 */
@Composable
private fun RuleDropdown(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
