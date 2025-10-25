package com.inksoftware.agent;

public interface Agent {

    String getName();
    
    //Checks if an instance of an agent can handle the user's question
    //Returns a score from 0.0 (can't handle) to 1.0 (perfect match)
    double canHandle(String userQuestion);

    String respond(String userQuestion);
}
