package com.example.tapiscontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WorkoutsTab(
    workout: Workout,
    onStartWorkout: () -> Unit = {},
    onEditWorkout: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    WorkoutsUi(
        workout = workout,
        onStartWorkout = onStartWorkout,
        onEditWorkout = onEditWorkout,
        onBack = onBack
    )
}

@Composable
fun WorkoutsUi(
    workout: Workout,
    onStartWorkout: () -> Unit,
    onEditWorkout: () -> Unit,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Nome dell'allenamento
            Text(
                text = workout.name,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            // Lista dei blocchi con numerazione progressiva globale degli step
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Contatore globale unico che attraversa tutti i blocchi senza azzerarsi
                var globalStepIndex = 1

                items(workout.blocks) { block ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = block.name.ifEmpty { "Blocco" },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Iterazione sicura su tutti gli step del blocco
                            block.steps.forEach { step ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Step $globalStepIndex",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = step.toString(),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                globalStepIndex++ // Incrementa progressivamente in modo continuo
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Pulsanti di navigazione inferiori
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Indietro")
                }
                Button(
                    onClick = onStartWorkout,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Avvia")
                }
            }
        }
    }
}
