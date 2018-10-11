define(["common/searchParser", "backbone", "common/session", "auth/login", 'header/header', 'user/userManagement'],
        function(searchParser, Backbone, session, login, header, userManagement){
    var Router = Backbone.Router.extend({
        routes: {
            "userManagement(/)" : "displayUserManagement",
            "login(/)" : "login",
            "logout(/)" : "logout",

            "*path" : "displayUserManagement"

        },
       
        initialize: function(){
            var pushState = history.pushState;
            history.pushState = function(state, title, path) {
            		if(state.trigger){
            			this.router.navigate(path, state);
            		}else{
            			this.router.navigate(path, {trigger: true});
            		}
                return pushState.apply(history, arguments);
            }.bind({router:this});
        },
       
        execute: function(callback, args, name){
            if( ! session.isValid()){
                this.login();
                return false;
            }
            if (callback) {
                callback.apply(this, args);
            }
        },
       
        login : function(){
            login.showLoginPage();
        },

        logout : function(){
            sessionStorage.clear();
            window.location = "/logout";
        },

        displayUserManagement : function(){
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);

            var userMngmt = new userManagement.View({model: new userManagement.Model()});
            userMngmt.render();
            $('#user-div').append(userMngmt.$el);
        }

    });
    return new Router();
});