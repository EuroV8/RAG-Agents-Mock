package com.inksoftware.agent;

import java.util.ArrayList;
import java.util.List;

import com.inksoftware.Main;

//Routes questions to the most suitable agent
public class AgentRouter {
    private final List<Agent> agents = new ArrayList<>();
    
    //Add agent to the router
    public void addAgent(Agent agent) {
        agents.add(agent);
        if(Main.DEBUG)
            System.out.println("[DEBUG] Added agent: " + agent.getName());
    }

    //Determine the best agent for a given question, provide answer and log it to history
    public String route(String userQuestion) {
        Agent bestAgent = null;
        double bestScore = 0.0;
        
        for(Agent agent : agents){
            double score = agent.canHandle(userQuestion);
            System.out.println("  " + agent.getName() + " score: " + score);
            
            if(score > bestScore){
                bestScore = score;
                bestAgent = agent;
            }
        }
        
        if (bestAgent != null && bestScore > 0.3) {
            if(Main.DEBUG)
                System.out.println("[DEBUG] Selected: " + bestAgent.getName());
            
            if(bestAgent instanceof AbstractAgent abstractAgent){          //add USER message to agent's chat history
                abstractAgent.addToChatHistory("User: " + userQuestion);
            }

            String response = bestAgent.respond(userQuestion);
            
            if(bestAgent instanceof AbstractAgent abstractAgent){           //add AGENT response to chat history
                abstractAgent.addToChatHistory(bestAgent.getName() + ": " + response);
            }
            return bestAgent.getName() + ": " + response;
        } 
        else{
            return "Helper: I'm not sure how to help with that. Try being more precise in your request about technical issues or billing.";
        }
    }
}
